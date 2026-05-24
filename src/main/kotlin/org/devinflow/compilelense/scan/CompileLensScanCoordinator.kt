package org.devinflow.compilelense.scan

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import org.devinflow.compilelense.build.CompileLensBuildTrigger

/**
 * Coordinates scans across every open project in the IDE window.
 */
internal object CompileLensScanCoordinator {

    fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    fun dumbProjects(): List<Project> =
        openProjects().filter { DumbService.getInstance(it).isDumb }

    fun allOpenProjectsSmart(): Boolean = dumbProjects().isEmpty()

    /**
     * Rebuilds every open project, restarts highlighting, then scans the workspace.
     */
    fun rescanWithBuild(trigger: Project, onFinished: (() -> Unit)? = null) {
        if (!allOpenProjectsSmart()) {
            runWhenAllProjectsSmart(trigger) { rescanWithBuild(trigger, onFinished) }
            return
        }
        CompileLensDebugLog.info(trigger, "rescanWithBuild: rebuilding all open projects")
        CompileLensBuildTrigger.rebuildOpenProjects(trigger) {
            refreshScan(trigger, restartDaemon = true)
            onFinished?.invoke()
        }
    }

    fun refreshAll(trigger: Project, restartDaemon: Boolean = true) {
        refreshScan(trigger, restartDaemon)
    }

    private fun refreshScan(trigger: Project, restartDaemon: Boolean) {
        for (project in openProjects()) {
            if (restartDaemon && !DumbService.getInstance(project).isDumb) {
                DaemonCodeAnalyzer.getInstance(project).restart("CompileLens refresh")
            }
        }
        runWhenAllProjectsSmart(trigger) {
            if (!trigger.isDisposed) {
                CompileLensScanService.getInstance(trigger).scheduleScan(immediate = true)
            }
        }
        CompileLensDebugLog.info(
            trigger,
            "refreshScan: projects=${openProjects().map { it.name }} restartDaemon=$restartDaemon",
        )
    }

    fun runWhenAllProjectsSmart(trigger: Project, action: () -> Unit) {
        val waitingOn = dumbProjects()
        if (waitingOn.isEmpty()) {
            action()
            return
        }
        CompileLensDebugLog.info(
            trigger,
            "waiting for smart mode: ${waitingOn.map { it.name }}",
        )
        var remaining = waitingOn.size
        for (project in waitingOn) {
            DumbService.getInstance(project).runWhenSmart {
                remaining -= 1
                if (remaining <= 0 && allOpenProjectsSmart()) {
                    action()
                }
            }
        }
    }
}
