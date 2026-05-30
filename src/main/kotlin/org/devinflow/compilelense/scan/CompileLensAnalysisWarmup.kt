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
import java.util.concurrent.atomic.AtomicLong

/**
 * IntelliJ only highlights and marks a project red after a file has been analyzed by the daemon
 * (usually when it is opened). This warmup schedules daemon analysis on closed files so
 * CompileLens can see their errors without the user visiting each class first.
 */
internal object CompileLensAnalysisWarmup {

    private const val MAX_FILES_PER_PROJECT = 300
    private const val WARMUP_TIMEOUT_MS = 120_000L

    /**
     * Quiet period after the most recent `daemonFinished` event before we conclude the daemon
     * is idle. Must be longer than the typical gap between two consecutive daemon passes so we
     * do not exit the warmup wait in the middle of an inter-pass pause.
     */
    private const val DAEMON_QUIET_MS = 750L

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

        val finished = restartAndAwaitDaemonIdle(project, daemon, filesToWarm)
        if (!finished) {
            CompileLensDebugLog.warn(
                logProject,
                "warmup timeout: project=${project.name} daemonStillRunning=true",
            )
        }

        return WarmupStats(filesToWarm.size, skipped)
    }

    /**
     * Issues daemon restarts for [filesToWarm] and waits until the daemon goes idle.
     *
     * The previous implementation polled `DaemonCodeAnalyzer.isRunning`, but that accessor is
     * marked `@ApiStatus.Internal` and is therefore off-limits for third-party plugins. We
     * replace the polling with a subscription to the public `DAEMON_EVENT_TOPIC`: every time the
     * daemon completes a pass it fires `daemonFinished()`. After the queued restarts run, we
     * consider the daemon idle once we have observed at least one `daemonFinished` event AND no
     * further events for [DAEMON_QUIET_MS] (so we don't bail out between two consecutive passes
     * the daemon performs over our batch of files).
     *
     * The listener is subscribed *before* we issue the restarts so we cannot miss the first
     * `daemonFinished` event in the rare case the daemon completes before we start waiting.
     */
    private fun restartAndAwaitDaemonIdle(
        project: Project,
        daemon: DaemonCodeAnalyzer,
        filesToWarm: List<PsiJavaFile>,
    ): Boolean {
        if (filesToWarm.isEmpty()) return true
        val lastFinishedNanos = AtomicLong(0L)
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(
                DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
                object : DaemonCodeAnalyzer.DaemonListener {
                    override fun daemonFinished() {
                        lastFinishedNanos.set(System.nanoTime())
                    }
                },
            )

            for (psiFile in filesToWarm) {
                ProgressManager.checkCanceled()
                daemon.restart(psiFile, "CompileLens warmup")
            }

            return waitForDaemonQuietPeriod(lastFinishedNanos)
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForDaemonQuietPeriod(lastFinishedNanos: AtomicLong): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARMUP_TIMEOUT_MS)
        val quietNanos = TimeUnit.MILLISECONDS.toNanos(DAEMON_QUIET_MS)
        while (System.nanoTime() < deadlineNanos) {
            ProgressManager.checkCanceled()
            if (isQuiet(lastFinishedNanos, quietNanos)) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            }
        }
        return isQuiet(lastFinishedNanos, quietNanos)
    }

    private fun isQuiet(lastFinishedNanos: AtomicLong, quietNanos: Long): Boolean {
        val last = lastFinishedNanos.get()
        return last != 0L && System.nanoTime() - last >= quietNanos
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
