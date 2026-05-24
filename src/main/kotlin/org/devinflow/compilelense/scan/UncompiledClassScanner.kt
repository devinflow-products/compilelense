package org.devinflow.compilelense.scan

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.toolWindow.HighlightingProblem
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.devinflow.compilelense.model.IssueType
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

internal object UncompiledClassScanner {

    private val warmedUpProjects = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun scan(triggeringProject: Project, previouslyTrackedPaths: Set<String> = emptySet()): List<UncompiledIssue> {
        val results = mutableListOf<UncompiledIssue>()
        val seen = mutableSetOf<String>()
        val projects = openProjects()

        CompileLensDebugLog.info(
            triggeringProject,
            "scan start: openProjects=${projects.map { it.name }}, triggering=${triggeringProject.name}",
        )

        if (shouldWarmup(projects)) {
            CompileLensAnalysisWarmup.ensureAnalyzed(projects, triggeringProject)
            projects.forEach { warmedUpProjects.add(it.locationHash) }
        }

        val candidates = collectCandidateFiles(projects, previouslyTrackedPaths)
        CompileLensDebugLog.info(
            triggeringProject,
            "candidates=${candidates.size}: ${candidates.take(10).map { it.name }}${if (candidates.size > 10) "..." else ""}",
        )

        collectIssues(projects, candidates, seen, results, triggeringProject)
        reconcileBuildErrorCache(triggeringProject, results, candidates)
        mergeBuildErrors(triggeringProject, seen, results)

        if (results.isEmpty()) {
            val allJava = collectAllJavaFiles(projects)
            CompileLensDebugLog.info(triggeringProject, "candidate pass empty; full scan javaFiles=${allJava.size}")
            collectIssues(projects, allJava, seen, results, triggeringProject)
            reconcileBuildErrorCache(triggeringProject, results, allJava)
            mergeBuildErrors(triggeringProject, seen, results)
        }

        CompileLensDebugLog.info(
            triggeringProject,
            "scan done: issues=${results.size} files=${results.map { it.fileName }.distinct()}",
        )
        results.forEach { issue ->
            CompileLensDebugLog.info(
                triggeringProject,
                "  -> ${issue.fileName}:${issue.lineNumber} ${issue.issueDetail}",
            )
        }

        return results.sortedWith(compareBy({ it.moduleName }, { it.fileName }, { it.lineNumber }))
    }

    private fun openProjects(): List<Project> =
        CompileLensScanCoordinator.openProjects().filter { !DumbService.isDumb(it) }

    private fun shouldWarmup(projects: List<Project>): Boolean =
        projects.any { !warmedUpProjects.contains(it.locationHash) }

    /**
     * Drops cached javac errors for files that no longer have live IDE diagnostics,
     * so fixing a file in the editor removes it from the dashboard without a full rebuild.
     */
    private fun reconcileBuildErrorCache(
        logProject: Project,
        results: List<UncompiledIssue>,
        scannedFiles: Set<VirtualFile>,
    ) {
        if (scannedFiles.isEmpty()) return
        val pathsWithLiveIssues = results.mapTo(HashSet()) { it.virtualFilePath }
        var pruned = 0
        for (file in scannedFiles) {
            if (file.path !in pathsWithLiveIssues) {
                val before = CompileLensBuildErrorCache.get(logProject).count { it.virtualFilePath == file.path }
                if (before > 0) {
                    CompileLensBuildErrorCache.removeFile(logProject, file.path)
                    pruned += before
                }
            }
        }
        if (pruned > 0) {
            CompileLensDebugLog.info(logProject, "pruned stale build errors: count=$pruned")
        }
    }

    private fun mergeBuildErrors(
        logProject: Project,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
    ) {
        val buildErrors = CompileLensBuildErrorCache.get(logProject)
        if (buildErrors.isEmpty()) return
        var added = 0
        for (issue in buildErrors) {
            val key = "${issue.virtualFilePath}:build:${issue.issueDetail.lowercase(Locale.getDefault())}:${issue.lineNumber}"
            val before = results.size
            addIssue(
                seen,
                results,
                key = key,
                className = issue.className,
                fileName = issue.fileName,
                packageName = issue.packageName,
                issueType = issue.issueType,
                issueSummary = issue.issueSummary,
                issueDetail = issue.issueDetail,
                moduleName = issue.moduleName,
                lineNumber = issue.lineNumber,
                filePath = issue.virtualFilePath,
            )
            if (results.size > before) added++
        }
        CompileLensDebugLog.info(logProject, "merged compiler build errors: count=$added")
    }

    private fun collectIssues(
        projects: List<Project>,
        files: Set<VirtualFile>,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        logProject: Project,
    ) {
        if (files.isEmpty()) return
        collectFromProblemsCollector(projects, files, seen, results, logProject)
        collectFromDocumentHighlights(projects, files, seen, results, logProject)
    }

    private fun collectCandidateFiles(projects: List<Project>, extraPaths: Set<String>): Set<VirtualFile> {
        val candidates = LinkedHashSet<VirtualFile>()
        val localFs = LocalFileSystem.getInstance()

        for (path in extraPaths) {
            localFs.findFileByPath(path)?.let { candidates.add(it) }
        }

        for (project in projects) {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val javaFiles = collectJavaFilesForProject(project, fileIndex)
            candidates.addAll(ProblemsCollector.getInstance(project).getProblemFiles())
            val problemSolver = WolfTheProblemSolver.getInstance(project)
            for (virtualFile in javaFiles) {
                if (problemSolver.isProblemFile(virtualFile) || problemSolver.hasSyntaxErrors(virtualFile)) {
                    candidates.add(virtualFile)
                }
            }
            for (openFile in FileEditorManager.getInstance(project).openFiles) {
                if (isScannableJava(openFile, fileIndex, includeOpenEditors = true)) {
                    candidates.add(openFile)
                }
            }
        }

        return candidates.filter { isJavaFile(it) }.toSet()
    }

    private fun collectAllJavaFiles(projects: List<Project>): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        for (project in projects) {
            files.addAll(collectJavaFilesForProject(project, ProjectFileIndex.getInstance(project)))
        }
        return files
    }

    private fun collectFromProblemsCollector(
        projects: List<Project>,
        files: Set<VirtualFile>,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        logProject: Project,
    ) {
        for (virtualFile in files) {
            val resolved = resolveJavaFile(projects, virtualFile) ?: run {
                CompileLensDebugLog.warn(logProject, "collector: no PSI for ${virtualFile.path}")
                continue
            }
            val (ownerProject, psiFile) = resolved
            val collector = ProblemsCollector.getInstance(ownerProject)
            val problems = collector.getFileProblems(virtualFile)
            if (problems.isEmpty()) {
                CompileLensDebugLog.info(logProject, "collector: ${virtualFile.name} problems=0")
                continue
            }

            val severityRegistrar = SeverityRegistrar.getSeverityRegistrar(ownerProject)
            val context = fileContext(ownerProject, virtualFile, psiFile)
            var added = 0
            var skipped = 0

            for (problem in problems) {
                if (!isErrorProblem(problem, severityRegistrar)) {
                    skipped++
                    continue
                }
                val line = problemLine(problem)
                val detail = problemMessage(problem)
                if (detail.isBlank() || isLoadingMessage(detail)) {
                    skipped++
                    continue
                }
                val before = results.size
                addIssue(
                    seen,
                    results,
                    key = "${virtualFile.path}:problem:${detail.lowercase(Locale.getDefault())}:$line",
                    className = context.className,
                    fileName = context.fileName,
                    packageName = context.packageName,
                    issueType = classifyDiagnosticIssue(detail),
                    issueSummary = classifyDiagnosticIssue(detail).displayName,
                    issueDetail = detail,
                    moduleName = context.moduleName,
                    lineNumber = line,
                    filePath = virtualFile.path,
                )
                if (results.size > before) added++
            }
            CompileLensDebugLog.info(
                logProject,
                "collector: ${virtualFile.name} total=${problems.size} added=$added skipped=$skipped",
            )
        }
    }

    private fun collectFromDocumentHighlights(
        projects: List<Project>,
        files: Set<VirtualFile>,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        logProject: Project,
    ) {
        for (virtualFile in files) {
            if (!virtualFile.isValid || virtualFile.isDirectory) continue
            val resolved = resolveJavaFile(projects, virtualFile) ?: continue
            val (ownerProject, psiFile) = resolved
            val document = documentFor(ownerProject, psiFile, virtualFile) ?: run {
                CompileLensDebugLog.warn(logProject, "highlights: no document for ${virtualFile.path}")
                continue
            }

            val severityRegistrar = SeverityRegistrar.getSeverityRegistrar(ownerProject)
            val highlights = collectErrorHighlights(ownerProject, document, severityRegistrar)
            if (highlights.isEmpty()) {
                CompileLensDebugLog.info(logProject, "highlights: ${virtualFile.name} count=0")
                continue
            }

            val context = fileContext(ownerProject, virtualFile, psiFile)
            var added = 0
            for (highlight in highlights) {
                val line = lineForOffset(document.textLength, highlight.actualStartOffset, document)
                val detail = sanitizeDiagnosticText(highlight.description ?: highlight.toolTip ?: "Compilation error")
                val issueType = classifyDiagnosticIssue(detail)
                val before = results.size
                addIssue(
                    seen,
                    results,
                    key = "${virtualFile.path}:highlight:${detail.lowercase(Locale.getDefault())}:$line",
                    className = context.className,
                    fileName = context.fileName,
                    packageName = context.packageName,
                    issueType = issueType,
                    issueSummary = issueType.displayName,
                    issueDetail = detail,
                    moduleName = context.moduleName,
                    lineNumber = line,
                    filePath = virtualFile.path,
                )
                if (results.size > before) added++
            }
            CompileLensDebugLog.info(logProject, "highlights: ${virtualFile.name} found=${highlights.size} added=$added")
        }
    }

    private fun resolveJavaFile(projects: List<Project>, virtualFile: VirtualFile): Pair<Project, PsiJavaFile>? {
        for (project in projects) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            if (psiFile != null) return project to psiFile
        }
        return null
    }

    private fun documentFor(project: Project, psiFile: PsiJavaFile, virtualFile: VirtualFile): Document? =
        FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: PsiDocumentManager.getInstance(project).getDocument(psiFile)

    /**
     * Walks the document markup model directly so we do not miss highlights filtered by Code Insight context.
     */
    private fun collectErrorHighlights(
        project: Project,
        document: Document,
        severityRegistrar: SeverityRegistrar,
    ): List<HighlightInfo> {
        val highlights = LinkedHashSet<HighlightInfo>()
        val model = DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx ?: return emptyList()
        model.processRangeHighlightersOverlappingWith(0, document.textLength) { marker ->
            val info = HighlightInfo.fromRangeHighlighter(marker) ?: return@processRangeHighlightersOverlappingWith true
            if (info.highlighter == marker && severityRegistrar.compare(info.severity, HighlightSeverity.ERROR) >= 0) {
                highlights.add(info)
            }
            true
        }
        return highlights.toList()
    }

    private data class FileContext(
        val className: String,
        val fileName: String,
        val packageName: String,
        val moduleName: String,
    )

    private fun fileContext(project: Project, virtualFile: VirtualFile, psiFile: PsiJavaFile): FileContext {
        val fileName = virtualFile.name
        return FileContext(
            className = psiFile.classes.firstOrNull()?.name ?: fileName.removeSuffix(".java"),
            fileName = fileName,
            packageName = psiFile.packageName.ifBlank { "(default package)" },
            moduleName = ModuleUtil.findModuleForFile(virtualFile, project)?.name ?: project.name,
        )
    }

    private fun isErrorProblem(problem: Problem, severityRegistrar: SeverityRegistrar): Boolean {
        val text = sanitizeDiagnosticText(problem.text)
        if (isLoadingMessage(text)) return false

        if (problem is HighlightingProblem) {
            problem.info?.let { return severityRegistrar.compare(it.severity, HighlightSeverity.ERROR) >= 0 }
            if (problem.severity >= HighlightSeverity.ERROR.myVal) return true
        }

        val lower = text.lowercase(Locale.getDefault())
        if (lower.contains("never used") || lower.contains("unused import")) return false
        return lower.contains("cannot resolve")
            || lower.contains("cannot find symbol")
            || lower.contains("identifier expected")
            || lower.contains("';' expected")
            || lower.contains("cannot access")
            || lower.contains("incompatible types")
            || lower.contains("syntax error")
    }

    private fun problemLine(problem: Problem): Int {
        val fileProblem = problem as? FileProblem ?: return 1
        val line = fileProblem.line
        return if (line >= 0) line + 1 else 1
    }

    private fun problemMessage(problem: Problem): String {
        val text = sanitizeDiagnosticText(problem.text)
        val description = problem.description?.let { sanitizeDiagnosticText(it) }
        return when {
            description != null && description != text && description.isNotBlank() -> description
            else -> text
        }
    }

    private fun collectJavaFilesForProject(project: Project, fileIndex: ProjectFileIndex): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        collectIndexedJavaFiles(project, GlobalSearchScope.projectScope(project), fileIndex, files)
        for (module in ModuleManager.getInstance(project).modules) {
            if (module.isDisposed) continue
            collectIndexedJavaFiles(project, GlobalSearchScope.moduleScope(module), fileIndex, files)
        }
        fileIndex.iterateContent { virtualFile ->
            if (isScannableJava(virtualFile, fileIndex)) files.add(virtualFile)
            true
        }
        return files
    }

    private fun collectIndexedJavaFiles(
        project: Project,
        scope: GlobalSearchScope,
        fileIndex: ProjectFileIndex,
        files: MutableSet<VirtualFile>,
    ) {
        for (virtualFile in FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)) {
            if (isScannableJava(virtualFile, fileIndex)) files.add(virtualFile)
        }
    }

    private fun isScannableJava(
        virtualFile: VirtualFile,
        fileIndex: ProjectFileIndex,
        includeOpenEditors: Boolean = false,
    ): Boolean {
        if (!virtualFile.isValid || virtualFile.isDirectory) return false
        if (!isJavaFile(virtualFile)) return false
        if (fileIndex.isInSourceContent(virtualFile)) return true
        return includeOpenEditors
    }

    private fun isJavaFile(virtualFile: VirtualFile): Boolean =
        virtualFile.extension?.equals("java", ignoreCase = true) == true

    private fun lineForOffset(textLength: Int, offset: Int, document: Document): Int {
        if (textLength <= 0) return 1
        val safeOffset = offset.coerceIn(0, textLength - 1)
        return document.getLineNumber(safeOffset) + 1
    }

    private fun sanitizeDiagnosticText(raw: String): String {
        val noHtml = raw.replace(Regex("<[^>]*>"), " ")
        return noHtml.replace(Regex("\\s+"), " ").trim().ifBlank { "Compilation error" }
    }

    private fun isLoadingMessage(text: String): Boolean =
        text.equals("Loading...", ignoreCase = true)

    private fun classifyDiagnosticIssue(detail: String): IssueType {
        val lower = detail.lowercase(Locale.getDefault())
        if ("import" in lower && ("cannot resolve" in lower || "cannot find symbol" in lower)) {
            return IssueType.UNRESOLVED_IMPORT
        }
        if ("package" in lower && "does not exist" in lower) {
            return IssueType.MISSING_DEPENDENCY
        }
        if ("cannot resolve symbol" in lower || "cannot find symbol" in lower || "cannot access" in lower) {
            return if ("class " in lower || "interface " in lower || "enum " in lower) {
                IssueType.CLASS_NOT_FOUND
            } else {
                IssueType.MISSING_DEPENDENCY
            }
        }
        if ("class not found" in lower || "cannot find class" in lower) {
            return IssueType.CLASS_NOT_FOUND
        }
        return IssueType.COMPILATION_ERROR
    }

    private fun addIssue(
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        key: String,
        className: String,
        fileName: String,
        packageName: String,
        issueType: IssueType,
        issueSummary: String,
        issueDetail: String,
        moduleName: String,
        lineNumber: Int,
        filePath: String,
    ) {
        if (!seen.add(key)) return
        val hasFixSuggestion = issueType == IssueType.MISSING_DEPENDENCY || issueType == IssueType.UNRESOLVED_IMPORT
        results += UncompiledIssue(
            className = className,
            fileName = fileName,
            packageName = packageName,
            issueType = issueType,
            issueSummary = issueSummary.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
            issueDetail = issueDetail,
            moduleName = moduleName,
            lineNumber = lineNumber,
            virtualFilePath = filePath,
            hasFixSuggestion = hasFixSuggestion,
        )
    }
}
