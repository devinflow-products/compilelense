package org.devinflow.compilelense.scan

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.devinflow.compilelense.model.IssueType
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

/**
 * Reads javac messages and maps them to [UncompiledIssue] rows.
 * Snapshots are taken on the compile callback thread; mapping runs on a background read action.
 */
internal object CompilerMessageScanner {

    private val MESSAGE_LOCATION = Regex("""^(.+\.java):(\d+):\s*(?:error:\s*)?(.+)$""", RegexOption.IGNORE_CASE)

    /** EDT-safe: extract paths/messages only, no PSI or file-index access. */
    fun snapshotErrors(compileContext: CompileContext): List<BuildMessageSnapshot> {
        val messages = compileContext.getMessages(CompilerMessageCategory.ERROR) ?: return emptyList()
        val snapshots = ArrayList<BuildMessageSnapshot>()
        val seen = HashSet<String>()
        for (message in messages) {
            val snapshot = snapshotFromMessage(message) ?: continue
            val key = "${snapshot.fileName}:${snapshot.line}:${snapshot.message.lowercase(Locale.getDefault())}"
            if (seen.add(key)) {
                snapshots += snapshot
            }
        }
        return snapshots
    }

    private fun snapshotFromMessage(message: CompilerMessage): BuildMessageSnapshot? {
        val detail = sanitize(message.message)
        if (detail.isBlank()) return null

        val virtualFile = lightweightVirtualFile(message)
        if (virtualFile != null) {
            return BuildMessageSnapshot(
                fileName = virtualFile.name,
                line = lineNumber(message, virtualFile).coerceAtLeast(1),
                message = detail,
                filePath = CompileLensPaths.normalize(virtualFile),
            )
        }

        val parsed = parseMessageLocation(detail) ?: return null
        return BuildMessageSnapshot(
            fileName = parsed.fileName,
            line = parsed.line,
            message = parsed.message.ifBlank { detail },
            filePath = parsed.filePath?.let { CompileLensPaths.normalize(it) },
        )
    }

    /** Must be called under a background [ReadAction], not on the EDT. */
    fun collectMessages(compileProject: Project, snapshots: List<BuildMessageSnapshot>): List<UncompiledIssue> {
        if (compileProject.isDisposed || snapshots.isEmpty()) return emptyList()
        val results = ArrayList<UncompiledIssue>()
        val seen = HashSet<String>()

        for (snapshot in snapshots) {
            val filePath = resolveFilePath(snapshot, compileProject) ?: continue
            val key = "$filePath:${snapshot.line}:${snapshot.message.lowercase(Locale.getDefault())}"
            if (!seen.add(key)) continue

            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
            if (virtualFile == null) {
                results += issueWithoutFile(compileProject, snapshot, filePath)
                continue
            }

            val resolved = resolveJavaFile(virtualFile, compileProject)
            val issue = if (resolved != null) {
                issueFromPsi(resolved.first, resolved.second, virtualFile, snapshot, filePath)
            } else {
                issueWithoutPsi(virtualFile, snapshot, compileProject, filePath)
            }
            results += issue
        }
        return results
    }

    private fun resolveFilePath(snapshot: BuildMessageSnapshot, compileProject: Project): String? {
        snapshot.filePath?.let { return CompileLensPaths.normalize(it) }
        return resolvePathByFileName(snapshot.fileName, compileProject)
    }

    private fun resolvePathByFileName(fileName: String, compileProject: Project): String? {
        val matches = ArrayList<VirtualFile>()
        for (project in CompileLensScanCoordinator.openProjects()) {
            val scope = GlobalSearchScope.projectScope(project)
            for (virtualFile in FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)) {
                if (virtualFile.name.equals(fileName, ignoreCase = true)) {
                    matches.add(virtualFile)
                }
            }
        }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return CompileLensPaths.normalize(matches.single())

        // Prefer source file owned by a different open project (dependency / attached module).
        for (virtualFile in matches) {
            for (project in CompileLensScanCoordinator.openProjects()) {
                if (project == compileProject) continue
                if (ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                    return CompileLensPaths.normalize(virtualFile)
                }
            }
        }
        return CompileLensPaths.normalize(matches.first())
    }

    private fun lightweightVirtualFile(message: CompilerMessage): VirtualFile? {
        message.virtualFile?.let { return it }
        val navigatable = message.navigatable
        if (navigatable is OpenFileDescriptor) {
            return navigatable.file
        }
        return null
    }

    private data class ParsedMessageLocation(
        val fileName: String,
        val filePath: String?,
        val line: Int,
        val message: String,
    )

    private fun parseMessageLocation(text: String): ParsedMessageLocation? {
        val match = MESSAGE_LOCATION.find(text.trim()) ?: return null
        val rawPath = match.groupValues[1].trim()
        val fileName = rawPath.substringAfterLast('/').substringAfterLast('\\')
        val line = match.groupValues[2].toIntOrNull() ?: return null
        val detail = match.groupValues[3].trim()
        return ParsedMessageLocation(
            fileName = fileName,
            filePath = rawPath,
            line = line,
            message = detail,
        )
    }

    private fun issueFromPsi(
        ownerProject: Project,
        psiFile: PsiJavaFile,
        virtualFile: VirtualFile,
        snapshot: BuildMessageSnapshot,
        filePath: String,
    ): UncompiledIssue {
        val fileName = virtualFile.name
        val className = psiFile.classes.firstOrNull()?.name ?: fileName.removeSuffix(".java")
        val issueType = classify(snapshot.message)
        return UncompiledIssue(
            className = className,
            fileName = fileName,
            packageName = psiFile.packageName.ifBlank { "(default package)" },
            issueType = issueType,
            issueSummary = issueType.displayName,
            issueDetail = snapshot.message,
            moduleName = moduleNameFor(virtualFile, ownerProject),
            projectName = ownerProject.name,
            lineNumber = snapshot.line,
            virtualFilePath = filePath,
            hasFixSuggestion = issueType == IssueType.MISSING_DEPENDENCY || issueType == IssueType.UNRESOLVED_IMPORT,
        )
    }

    private fun issueWithoutPsi(
        virtualFile: VirtualFile,
        snapshot: BuildMessageSnapshot,
        compileProject: Project,
        filePath: String,
    ): UncompiledIssue {
        val ownerProject = resolveOwnerProject(virtualFile, compileProject)
        val fileName = virtualFile.name
        val issueType = classify(snapshot.message)
        return UncompiledIssue(
            className = fileName.removeSuffix(".java"),
            fileName = fileName,
            packageName = inferPackageFromPath(filePath),
            issueType = issueType,
            issueSummary = issueType.displayName,
            issueDetail = snapshot.message,
            moduleName = moduleNameFor(virtualFile, ownerProject),
            projectName = ownerProject.name,
            lineNumber = snapshot.line,
            virtualFilePath = filePath,
            hasFixSuggestion = false,
        )
    }

    private fun issueWithoutFile(
        compileProject: Project,
        snapshot: BuildMessageSnapshot,
        filePath: String,
    ): UncompiledIssue {
        val issueType = classify(snapshot.message)
        return UncompiledIssue(
            className = snapshot.fileName.removeSuffix(".java"),
            fileName = snapshot.fileName,
            packageName = inferPackageFromPath(filePath),
            issueType = issueType,
            issueSummary = issueType.displayName,
            issueDetail = snapshot.message,
            moduleName = compileProject.name,
            projectName = compileProject.name,
            lineNumber = snapshot.line,
            virtualFilePath = filePath,
            hasFixSuggestion = false,
        )
    }

    private fun resolveJavaFile(virtualFile: VirtualFile, compileProject: Project): Pair<Project, PsiJavaFile>? {
        for (project in CompileLensScanCoordinator.openProjects()) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            if (psiFile != null) return project to psiFile
        }
        val fallback = PsiManager.getInstance(compileProject).findFile(virtualFile) as? PsiJavaFile
        return if (fallback != null) compileProject to fallback else null
    }

    private fun resolveOwnerProject(virtualFile: VirtualFile, fallback: Project): Project {
        for (project in CompileLensScanCoordinator.openProjects()) {
            if (PsiManager.getInstance(project).findFile(virtualFile) != null) return project
        }
        for (project in CompileLensScanCoordinator.openProjects()) {
            if (ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) return project
        }
        return fallback
    }

    private fun moduleNameFor(virtualFile: VirtualFile, ownerProject: Project): String {
        for (project in CompileLensScanCoordinator.openProjects()) {
            if (ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return ModuleUtil.findModuleForFile(virtualFile, project)?.name ?: project.name
            }
        }
        return ownerProject.name
    }

    private fun inferPackageFromPath(filePath: String): String {
        val normalized = filePath.replace('\\', '/')
        val marker = "/src/main/java/"
        val index = normalized.indexOf(marker)
        if (index < 0) return "(default package)"
        val afterSourceRoot = normalized.substring(index + marker.length)
        val packagePath = afterSourceRoot.substringBeforeLast('/')
        if (packagePath.isBlank()) return "(default package)"
        return packagePath.replace('/', '.')
    }

    private fun lineNumber(message: CompilerMessage, virtualFile: VirtualFile): Int {
        val navigatable = message.navigatable
        if (navigatable is OpenFileDescriptor) {
            if (navigatable.file == virtualFile || navigatable.file?.path == virtualFile.path) {
                return navigatable.line + 1
            }
        }
        return 1
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun classify(detail: String): IssueType {
        val lower = detail.lowercase(Locale.getDefault())
        if ("import" in lower && ("cannot find symbol" in lower || "cannot resolve" in lower)) {
            return IssueType.UNRESOLVED_IMPORT
        }
        if ("package" in lower && "does not exist" in lower) {
            return IssueType.MISSING_DEPENDENCY
        }
        return IssueType.COMPILATION_ERROR
    }
}
