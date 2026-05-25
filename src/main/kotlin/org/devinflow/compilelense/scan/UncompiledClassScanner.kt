package org.devinflow.compilelense.scan

import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.problems.WolfTheProblemSolver
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

internal object UncompiledClassScanner {

    fun scan(
        triggeringProject: Project,
        previouslyTrackedPaths: Set<String> = emptySet(),
        mode: CompileLensScanMode = CompileLensScanMode.FULL_WORKSPACE,
    ): CompileLensScanResult {
        val results = mutableListOf<UncompiledIssue>()
        val seen = mutableSetOf<String>()
        val projects = openProjects()

        CompileLensDebugLog.info(
            triggeringProject,
            "scan start: mode=$mode openProjects=${projects.map { it.name }}, triggering=${triggeringProject.name}",
        )

        val allSources = collectAllJavaFiles(projects)
        CompileLensDebugLog.info(triggeringProject, "java sources=${allSources.size}")

        val openPathsByProject = buildOpenPathsByProject(projects)
        val priorityPaths = buildPriorityPaths(projects, previouslyTrackedPaths, openPathsByProject)
        val filesToCollect = selectFilesToCollect(triggeringProject, allSources, mode, priorityPaths)
        val daemonRestartsByProject = selectDaemonRestartsByProject(
            projects,
            allSources,
            mode,
            priorityPaths,
            openPathsByProject,
        )

        mergeBuildErrors(projects, seen, results)
        collectIssues(projects, filesToCollect, seen, results, openPathsByProject)

        val analyzedPaths = daemonRestartsByProject.values.flatten().map { it.path }.toSet()
        updateAnalysisSession(triggeringProject, filesToCollect, results, analyzedPaths)

        val sorted = results.sortedWith(compareBy({ it.projectName }, { it.moduleName }, { it.fileName }, { it.lineNumber }))
        CompileLensDebugLog.info(
            triggeringProject,
            "scan done: mode=$mode collected=${filesToCollect.size} issues=${sorted.size} daemonFiles=${analyzedPaths.size}",
        )
        sorted.forEach { issue ->
            CompileLensDebugLog.info(
                triggeringProject,
                "  -> ${issue.fileName}:${issue.lineNumber} ${issue.issueDetail}",
            )
        }

        return CompileLensScanResult(
            issues = sorted,
            scannedFilePaths = filesToCollect.map { CompileLensPaths.normalize(it) }.toSet(),
            incremental = mode == CompileLensScanMode.INCREMENTAL,
            daemonRestartsByProject = daemonRestartsByProject.mapKeys { CompileLensScanResult.ProjectRef(it.key) },
        )
    }

    private fun selectFilesToCollect(
        triggeringProject: Project,
        allSources: Set<VirtualFile>,
        mode: CompileLensScanMode,
        priorityPaths: Set<String>,
    ): Set<VirtualFile> {
        if (mode == CompileLensScanMode.FULL_WORKSPACE) return allSources
        return allSources.filter { virtualFile ->
            val path = CompileLensPaths.normalize(virtualFile)
            path in priorityPaths || CompileLensAnalysisSession.isDirtyInAnyProject(path)
        }.toSet()
    }

    private fun openProjects(): List<Project> =
        CompileLensScanCoordinator.openProjects().filter { !DumbService.isDumb(it) }

    private fun buildOpenPathsByProject(projects: List<Project>): Map<Project, Set<String>> =
        projects.associateWith { project ->
            FileEditorManager.getInstance(project).openFiles.mapTo(HashSet()) { CompileLensPaths.normalize(it) }
        }

    private fun buildPriorityPaths(
        projects: List<Project>,
        previouslyTrackedPaths: Set<String>,
        openPathsByProject: Map<Project, Set<String>>,
    ): Set<String> {
        val paths = LinkedHashSet<String>()
        paths.addAll(previouslyTrackedPaths.map { CompileLensPaths.normalize(it) })
        for (project in projects) {
            paths.addAll(ProblemsCollector.getInstance(project).getProblemFiles().map { CompileLensPaths.normalize(it) })
            paths.addAll(openPathsByProject[project].orEmpty().map { CompileLensPaths.normalize(it) })
            paths.addAll(CompileLensBuildErrorCache.get(project).map { CompileLensPaths.normalize(it.virtualFilePath) })
        }
        return paths
    }

    private fun selectDaemonRestartsByProject(
        projects: List<Project>,
        allSources: Set<VirtualFile>,
        mode: CompileLensScanMode,
        priorityPaths: Set<String>,
        openPathsByProject: Map<Project, Set<String>>,
    ): Map<Project, List<VirtualFile>> {
        val restarts = LinkedHashMap<Project, MutableList<VirtualFile>>()
        for (project in projects) {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val problemSolver = WolfTheProblemSolver.getInstance(project)
            val openPaths = openPathsByProject[project].orEmpty()
            for (virtualFile in allSources) {
                if (!belongsToProject(virtualFile, project, fileIndex)) continue
                if (!shouldScheduleDaemon(project, virtualFile, mode, priorityPaths, openPaths, problemSolver)) continue
                restarts.getOrPut(project) { ArrayList() }.add(virtualFile)
            }
        }
        return restarts
    }

    private fun belongsToProject(
        virtualFile: VirtualFile,
        project: Project,
        fileIndex: ProjectFileIndex,
    ): Boolean {
        if (!virtualFile.isValid) return false
        val module = ModuleUtil.findModuleForFile(virtualFile, project) ?: return false
        return fileIndex.isInSourceContent(virtualFile)
    }

    private fun shouldScheduleDaemon(
        project: Project,
        virtualFile: VirtualFile,
        mode: CompileLensScanMode,
        priorityPaths: Set<String>,
        openPaths: Set<String>,
        problemSolver: WolfTheProblemSolver,
    ): Boolean {
        val path = virtualFile.path
        when (mode) {
            CompileLensScanMode.FULL_WORKSPACE -> {
                if (CompileLensAnalysisSession.isDirty(project, path)) return true
                if (path in openPaths) return true
                if (CompileLensAnalysisSession.wasAnalyzed(project, path) &&
                    CompileLensAnalysisSession.isKnownClean(project, path)
                ) {
                    return false
                }
                if (problemSolver.isProblemFile(virtualFile) || problemSolver.hasSyntaxErrors(virtualFile)) {
                    return false
                }
                return true
            }
            CompileLensScanMode.INCREMENTAL -> {
                if (path !in priorityPaths && path !in openPaths &&
                    !CompileLensAnalysisSession.isDirty(project, path)
                ) {
                    return false
                }
                return true
            }
        }
    }

    private fun updateAnalysisSession(
        logProject: Project,
        collectedFiles: Set<VirtualFile>,
        results: List<UncompiledIssue>,
        daemonScheduledPaths: Set<String>,
    ) {
        val pathsWithIssues = results.map { it.virtualFilePath }.toSet()
        for (virtualFile in collectedFiles) {
            val path = virtualFile.path
            if (path !in daemonScheduledPaths && path !in pathsWithIssues &&
                !CompileLensAnalysisSession.wasAnalyzed(logProject, path)
            ) {
                continue
            }
            if (path in pathsWithIssues) {
                CompileLensAnalysisSession.clearCleanMark(logProject, path)
            } else if (path in daemonScheduledPaths || CompileLensAnalysisSession.wasAnalyzed(logProject, path)) {
                CompileLensAnalysisSession.markClean(logProject, path)
            }
        }
    }

    private fun mergeBuildErrors(
        projects: List<Project>,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
    ) {
        var added = 0
        for (openProject in projects) {
            for (issue in CompileLensBuildErrorCache.get(openProject)) {
                // Always merge javac build errors; collectIssues will drop them only when
                // the IntelliJ side confirms the file is structurally clean (no live
                // errors, no PsiErrorElement, not flagged by Wolf).
                val key = buildKey(issue)
                val before = results.size
                if (seen.add(key)) {
                    results += issue
                }
                if (results.size > before) added++
            }
        }
        if (added > 0) {
            CompileLensDebugLog.info(projects.firstOrNull() ?: return, "merged compiler build errors: count=$added")
        }
    }

    private fun buildKey(issue: UncompiledIssue): String =
        "${issue.virtualFilePath}:build:${issue.issueDetail.lowercase(Locale.getDefault())}:${issue.lineNumber}"

    private fun collectIssues(
        projects: List<Project>,
        files: Set<VirtualFile>,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        openPathsByProject: Map<Project, Set<String>>,
    ) {
        if (files.isEmpty()) return
        for (virtualFile in files) {
            val resolved = resolveJavaFile(projects, virtualFile) ?: continue
            val (ownerProject, psiFile) = resolved
            val normalizedPath = CompileLensPaths.normalize(virtualFile)
            val openPaths = openPathsByProject[ownerProject].orEmpty()
            val isOpen = normalizedPath in openPaths
            val fileIssues = CompileLensJavaFileAnalyzer.analyzeFile(
                ownerProject,
                virtualFile,
                psiFile,
                isOpen,
            )
            if (isOpen && isAuthoritativelyClean(ownerProject, virtualFile, psiFile, fileIssues)) {
                // Open file with NO live errors AND no PSI-level error elements: this is
                // a strong "user fixed it in-IDE" signal. Drop both the live and the
                // javac build entries (cache included) so the dashboard reflects the fix
                // immediately, without requiring a rebuild.
                removeAllIssuesForPath(normalizedPath, results, seen)
                clearBuildErrorsForPath(projects, normalizedPath)
            } else {
                // Otherwise refresh only the live (PSI/highlight/problem) entries for
                // this file and keep any javac build error already merged. javac is
                // authoritative for compilation status until a real build replaces it.
                removeLiveIssuesForPath(normalizedPath, results, seen)
                for (issue in fileIssues) {
                    val key = issueKey(issue)
                    if (seen.add(key)) {
                        results += issue
                    }
                }
            }
        }
    }

    private fun issueKey(issue: UncompiledIssue): String =
        "${issue.virtualFilePath}:${issue.issueDetail.lowercase(Locale.getDefault())}:${issue.lineNumber}"

    /**
     * Drops only "live" (PSI/highlight/problem) issues for [normalizedPath].
     * javac build issues already merged in this scan are kept — used for off-screen
     * files where we have no authoritative live signal.
     */
    private fun removeLiveIssuesForPath(
        normalizedPath: String,
        results: MutableList<UncompiledIssue>,
        seen: MutableSet<String>,
    ) {
        val iterator = results.iterator()
        while (iterator.hasNext()) {
            val issue = iterator.next()
            if (CompileLensPaths.normalize(issue.virtualFilePath) != normalizedPath) continue
            if (buildKey(issue) in seen) continue
            seen.remove(issueKey(issue))
            iterator.remove()
        }
    }

    /**
     * Drops every results entry for [normalizedPath], live and build alike. Used only
     * when we have an authoritative live signal (the file is currently open in the
     * editor and the IntelliJ daemon has had a chance to analyze it).
     */
    private fun removeAllIssuesForPath(
        normalizedPath: String,
        results: MutableList<UncompiledIssue>,
        seen: MutableSet<String>,
    ) {
        val iterator = results.iterator()
        while (iterator.hasNext()) {
            val issue = iterator.next()
            if (CompileLensPaths.normalize(issue.virtualFilePath) != normalizedPath) continue
            seen.remove(issueKey(issue))
            seen.remove(buildKey(issue))
            iterator.remove()
        }
    }

    private fun clearBuildErrorsForPath(projects: List<Project>, normalizedPath: String) {
        for (project in projects) {
            CompileLensBuildErrorCache.removeFile(project, normalizedPath)
        }
    }

    /**
     * Strong "file is genuinely clean per IntelliJ" check. Used to decide whether a
     * stale javac build error can be evicted without waiting for a rebuild.
     *
     * Both conditions must hold:
     *  - the live analyzer (DocumentMarkupModel + ProblemsCollector) reported no
     *    error-severity findings right now;
     *  - the PSI tree contains no [PsiErrorElement] (the file parses end-to-end).
     *
     * The PSI check guards against IntelliJ's recovering parser silently masking real
     * javac structural errors like "reached end of file while parsing".
     *
     * We deliberately do NOT consult [WolfTheProblemSolver]: it flags any kind of
     * problem, including warnings such as "unused import" — which would keep a file
     * pinned to the dashboard after a compile-clean fix that happens to leave imports
     * dangling (e.g. commenting out a whole method body).
     */
    private fun isAuthoritativelyClean(
        @Suppress("UNUSED_PARAMETER") project: Project,
        @Suppress("UNUSED_PARAMETER") virtualFile: VirtualFile,
        psiFile: PsiJavaFile,
        fileIssues: List<UncompiledIssue>,
    ): Boolean {
        if (fileIssues.isNotEmpty()) return false
        if (PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement::class.java) != null) return false
        return true
    }

    private fun collectAllJavaFiles(projects: List<Project>): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        for (project in projects) {
            files.addAll(collectJavaFilesForProject(project, ProjectFileIndex.getInstance(project)))
        }
        return files
    }

    private fun resolveJavaFile(projects: List<Project>, virtualFile: VirtualFile): Pair<Project, PsiJavaFile>? {
        for (project in projects) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile
            if (psiFile != null) return project to psiFile
        }
        return null
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

    private fun isScannableJava(virtualFile: VirtualFile, fileIndex: ProjectFileIndex): Boolean {
        if (!virtualFile.isValid || virtualFile.isDirectory) return false
        if (virtualFile.extension?.equals("java", ignoreCase = true) != true) return false
        return fileIndex.isInSourceContent(virtualFile)
    }
}
