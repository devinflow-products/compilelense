package org.devinflow.compilelense.scan

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager

/**
 * Schedules [DaemonCodeAnalyzer] restarts on the EDT (required by the platform).
 * Collection of problems runs later in a background [ReadAction].
 */
internal object CompileLensWorkspaceAnalysis {

    private const val MAX_RESTARTS_PER_PASS = 200

    /**
     * Requests daemon analysis for [virtualFiles] without blocking the caller.
     * Must not be called from a background read action with file-index work on the EDT.
     */
    fun scheduleDaemonRestarts(
        project: Project,
        virtualFiles: Collection<VirtualFile>,
        reason: String,
    ) {
        if (virtualFiles.isEmpty() || project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || DumbService.isDumb(project)) return@invokeLater
            restartOnEdt(project, virtualFiles, reason)
        }, ModalityState.defaultModalityState())
    }

    /**
     * Global daemon restart for all open editors / pending highlights in the project.
     */
    fun scheduleDaemonRestartForVirtualFile(virtualFile: VirtualFile, reason: String) {
        if (!virtualFile.isValid || virtualFile.isDirectory) return
        for (project in CompileLensScanCoordinator.openProjects()) {
            if (project.isDisposed || DumbService.isDumb(project)) continue
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue
            if (!DaemonCodeAnalyzer.getInstance(project).isHighlightingAvailable(psiFile)) continue
            scheduleDaemonRestarts(project, listOf(virtualFile), reason)
            return
        }
    }

    fun scheduleProjectDaemonRestart(project: Project, reason: String) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || DumbService.isDumb(project)) return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(reason)
        }, ModalityState.defaultModalityState())
    }

    private fun restartOnEdt(project: Project, virtualFiles: Collection<VirtualFile>, reason: String) {
        val daemon = DaemonCodeAnalyzer.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        var restarted = 0
        for (virtualFile in virtualFiles) {
            if (restarted >= MAX_RESTARTS_PER_PASS) break
            if (!virtualFile.isValid) continue
            val psiFile = psiManager.findFile(virtualFile) as? PsiJavaFile ?: continue
            if (!daemon.isHighlightingAvailable(psiFile)) continue
            daemon.restart(psiFile, reason)
            CompileLensAnalysisSession.markAnalyzed(project, virtualFile.path)
            restarted++
        }
        if (restarted > 0) {
            CompileLensDebugLog.info(project, "daemon restart scheduled: count=$restarted reason=$reason")
        }
    }
}
