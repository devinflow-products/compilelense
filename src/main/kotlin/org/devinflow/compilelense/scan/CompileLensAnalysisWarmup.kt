package org.devinflow.compilelense.scan

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.util.concurrent.TimeUnit

/**
 * IntelliJ only highlights and marks a project red after a file has been analyzed by the daemon
 * (usually when it is opened). This warmup schedules daemon analysis on closed files so
 * CompileLens can see their errors without the user visiting each class first.
 */
internal object CompileLensAnalysisWarmup {

    private const val MAX_FILES_PER_PROJECT = 300
    private const val WARMUP_TIMEOUT_MS = 120_000L

    fun ensureAnalyzed(projects: List<Project>, logProject: Project) {
        var scheduled = 0
        var skipped = 0
        for (project in projects) {
            val result = scheduleWarmupForProject(project, logProject)
            scheduled += result.scheduled
            skipped += result.skipped
        }
        CompileLensDebugLog.info(
            logProject,
            "warmup done: scheduled=$scheduled skippedAlreadyAnalyzed=$skipped projects=${projects.map { it.name }}",
        )
    }

    private fun scheduleWarmupForProject(project: Project, logProject: Project): WarmupStats {
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val documentManager = PsiDocumentManager.getInstance(project)
        val severityRegistrar = SeverityRegistrar.getSeverityRegistrar(project)
        val problemSolver = WolfTheProblemSolver.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        val filesToWarm = ArrayList<PsiJavaFile>()
        var skipped = 0

        for (virtualFile in javaSourceFiles(project, fileIndex)) {
            ProgressManager.checkCanceled()
            if (filesToWarm.size >= MAX_FILES_PER_PROJECT) break

            if (problemSolver.isProblemFile(virtualFile)) {
                skipped++
                continue
            }
            val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: continue
            val document = documentManager.getDocument(psiFile)
                ?: FileDocumentManager.getInstance().getDocument(virtualFile)
                ?: continue
            if (hasErrorHighlights(project, document, severityRegistrar)) {
                skipped++
                continue
            }
            if (!daemon.isHighlightingAvailable(psiFile)) continue
            filesToWarm.add(psiFile)
        }

        if (filesToWarm.isEmpty()) {
            return WarmupStats(0, skipped)
        }

        CompileLensDebugLog.info(
            logProject,
            "warmup scheduling: project=${project.name} files=${filesToWarm.size}",
        )

        for (psiFile in filesToWarm) {
            ProgressManager.checkCanceled()
            daemon.restart(psiFile, "CompileLens warmup")
        }

        val finished = waitForDaemonIdle(daemon)
        if (!finished) {
            CompileLensDebugLog.warn(
                logProject,
                "warmup timeout: project=${project.name} daemonStillRunning=true",
            )
        }

        return WarmupStats(filesToWarm.size, skipped)
    }

    private fun waitForDaemonIdle(daemon: DaemonCodeAnalyzer): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARMUP_TIMEOUT_MS)
        while (System.nanoTime() < deadlineNanos) {
            ProgressManager.checkCanceled()
            if (!daemon.isRunning) {
                return true
            }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            }
        }
        return !daemon.isRunning
    }

    private fun hasErrorHighlights(
        project: Project,
        document: Document,
        severityRegistrar: SeverityRegistrar,
    ): Boolean {
        val model = DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx ?: return false
        var found = false
        model.processRangeHighlightersOverlappingWith(0, document.textLength) { marker ->
            val info = HighlightInfo.fromRangeHighlighter(marker) ?: return@processRangeHighlightersOverlappingWith true
            if (info.highlighter == marker && severityRegistrar.compare(info.severity, HighlightSeverity.ERROR) >= 0) {
                found = true
                return@processRangeHighlightersOverlappingWith false
            }
            true
        }
        return found
    }

    private fun javaSourceFiles(project: Project, fileIndex: ProjectFileIndex): Set<VirtualFile> {
        val files = LinkedHashSet<VirtualFile>()
        collectIndexed(project, GlobalSearchScope.projectScope(project), fileIndex, files)
        for (module in ModuleManager.getInstance(project).modules) {
            if (module.isDisposed) continue
            collectIndexed(project, GlobalSearchScope.moduleScope(module), fileIndex, files)
        }
        fileIndex.iterateContent { virtualFile ->
            if (isJavaSource(virtualFile, fileIndex)) files.add(virtualFile)
            true
        }
        return files
    }

    private fun collectIndexed(
        project: Project,
        scope: GlobalSearchScope,
        fileIndex: ProjectFileIndex,
        files: MutableSet<VirtualFile>,
    ) {
        for (virtualFile in FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)) {
            if (isJavaSource(virtualFile, fileIndex)) files.add(virtualFile)
        }
    }

    private fun isJavaSource(virtualFile: VirtualFile, fileIndex: ProjectFileIndex): Boolean {
        if (!virtualFile.isValid || virtualFile.isDirectory) return false
        if (virtualFile.extension?.equals("java", ignoreCase = true) != true) return false
        return fileIndex.isInSourceContent(virtualFile)
    }

    private data class WarmupStats(val scheduled: Int, val skipped: Int)
}
