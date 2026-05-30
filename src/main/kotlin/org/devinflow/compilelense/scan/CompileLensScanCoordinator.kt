package org.devinflow.compilelense.scan

import com.intellij.openapi.project.Project
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

/**
 * Workspace-wide scan coordination shared by every open project window.
 */
object CompileLensScanCoordinator {

    private val workspaceSnapshotReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val startupWithBuildStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val rebuildInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val rebuildQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    fun openProjects(): List<Project> =
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    fun dumbProjects(): List<Project> =
        openProjects().filter { com.intellij.openapi.project.DumbService.getInstance(it).isDumb }

    fun allOpenProjectsSmart(): Boolean = dumbProjects().isEmpty()

    fun isWorkspaceSnapshotReady(): Boolean = workspaceSnapshotReady.get()

    fun markWorkspaceSnapshotReady() {
        workspaceSnapshotReady.set(true)
    }

    fun resetWorkspaceSnapshotReady() {
        workspaceSnapshotReady.set(false)
    }

    fun isRebuildInProgress(): Boolean = rebuildInProgress.get()

    fun aggregateWorkspaceIssues(): List<UncompiledIssue> =
        openProjects()
            .flatMap { CompilationErrorService.getInstance(it).getAllIssues() }
            .let { dedupeIssues(it) }

    fun aggregateWorkspaceSnapshot(): org.devinflow.compilelense.model.DashboardSnapshot {
        val lastScan = openProjects()
            .mapNotNull { openProject ->
                openProject.takeIf { !it.isDisposed }
                    ?.let { CompileLensScanService.getInstance(it).getSnapshot().lastScan }
            }
            .maxOrNull() ?: java.time.Instant.EPOCH
        return org.devinflow.compilelense.model.DashboardSnapshot(aggregateWorkspaceIssues(), lastScan)
    }

    fun clearAllErrorServices() {
        for (openProject in openProjects()) {
            if (!openProject.isDisposed) {
                CompilationErrorService.getInstance(openProject).clearDashboard()
            }
        }
    }

    fun syncWorkspaceIssues(issues: List<UncompiledIssue>) {
        val deduped = dedupeIssues(issues)
        for (openProject in openProjects()) {
            if (!openProject.isDisposed) {
                CompilationErrorService.getInstance(openProject).replaceAll(deduped)
            }
        }
    }

    fun mergeScanIntoWorkspace(
        scannedFilePaths: Set<String>,
        scannedIssues: List<UncompiledIssue>,
        fullReplace: Boolean,
    ): List<UncompiledIssue> {
        if (fullReplace) return dedupeIssues(scannedIssues)
        val normalizedScanned = scannedFilePaths.map { CompileLensPaths.normalize(it) }.toSet()
        val preserved = aggregateWorkspaceIssues()
            .filter { CompileLensPaths.normalize(it.virtualFilePath) !in normalizedScanned }
        return dedupeIssues(preserved + scannedIssues)
    }

    fun dedupeIssues(issues: List<UncompiledIssue>): List<UncompiledIssue> =
        issues
            .groupBy { "${CompileLensPaths.normalize(it.virtualFilePath)}:${issueSymbolKey(it.issueDetail)}" }
            .map { (_, group) -> group.maxBy { issueDedupePriority(it) } }
            .sortedWith(compareBy({ it.projectName }, { it.moduleName }, { it.fileName }, { it.lineNumber }))

    /**
     * Groups IDE and javac diagnostics that refer to the same unresolved symbol on a file.
     * Falls back to the full message when no symbol can be extracted.
     */
    private fun issueSymbolKey(detail: String): String {
        val lower = detail.lowercase(Locale.getDefault())
        val quoted = Regex("""cannot resolve symbol '([^']+)'""").find(lower)?.groupValues?.get(1)
        if (quoted != null) return "sym:$quoted"
        val javacSymbol = Regex("""symbol:\s*(?:class|method|variable)\s+(\S+)""").find(lower)?.groupValues?.get(1)
        if (javacSymbol != null) return "sym:$javacSymbol"
        return "msg:${lower.replace(Regex("\\s+"), " ").trim()}"
    }

    /**
     * Prefer live IDE diagnostics over stale javac rows, and prefer the latest line number
     * when imports/edits shift code (javac line numbers go stale until the next rebuild).
     */
    private fun issueDedupePriority(issue: UncompiledIssue): Int {
        var score = issue.lineNumber
        if (!issue.issueDetail.trimStart().startsWith("java:", ignoreCase = true)) {
            score += 10_000
        }
        return score
    }

    /**
     * One-time workspace bootstrap: rebuild every open project so javac errors populate the dashboard
     * for files that were never opened in the editor.
     */
    fun runStartupWithBuild(trigger: Project) {
        if (trigger.isDisposed || !startupWithBuildStarted.compareAndSet(false, true)) return
        CompileLensDebugLog.info(trigger, "startup: automatic rebuild of all open projects")
        requestWorkspaceRebuild(trigger, "startup baseline") {
            CompileLensDebugLog.info(trigger, "startup: baseline rebuild finished")
        }
    }

    /**
     * Queues a workspace rebuild; coalesces overlapping requests while a rebuild is already running.
     */
    fun requestWorkspaceRebuild(trigger: Project, reason: String, onFinished: (() -> Unit)? = null) {
        if (trigger.isDisposed) return
        if (rebuildInProgress.get()) {
            rebuildQueued.set(true)
            CompileLensDebugLog.info(trigger, "rebuild queued: $reason")
            onFinished?.invoke()
            return
        }
        rebuildInProgress.set(true)
        CompileLensDebugLog.info(trigger, "rebuild requested: $reason")
        rescanWithBuild(trigger) {
            rebuildInProgress.set(false)
            onFinished?.invoke()
            if (rebuildQueued.compareAndSet(true, false) && !trigger.isDisposed) {
                requestWorkspaceRebuild(trigger, "queued rebuild")
            }
        }
    }

    /**
     * Rebuilds every open project, restarts highlighting, then scans the workspace.
     */
    fun rescanWithBuild(trigger: Project, onFinished: (() -> Unit)? = null) {
        if (!allOpenProjectsSmart()) {
            runWhenAllProjectsSmart(trigger) { rescanWithBuild(trigger, onFinished) }
            return
        }
        CompileLensDebugLog.info(trigger, "rescanWithBuild: rebuilding all open projects")
        org.devinflow.compilelense.build.CompileLensBuildTrigger.rebuildOpenProjects(trigger) {
            refreshScan(trigger, restartDaemon = true)
            onFinished?.invoke()
        }
    }

    fun refreshAll(trigger: Project, restartDaemon: Boolean = true) {
        refreshScan(trigger, restartDaemon)
    }

    private fun refreshScan(trigger: Project, restartDaemon: Boolean) {
        resetWorkspaceSnapshotReady()
        if (restartDaemon) {
            for (project in openProjects()) {
                if (!com.intellij.openapi.project.DumbService.getInstance(project).isDumb) {
                    CompileLensWorkspaceAnalysis.scheduleProjectDaemonRestart(project, "CompileLens refresh")
                }
            }
        }
        runWhenAllProjectsSmart(trigger) {
            if (!trigger.isDisposed) {
                CompileLensScanService.getInstance(trigger).scheduleScan(immediate = true, fullWorkspace = true)
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
            com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart {
                remaining -= 1
                if (remaining <= 0 && allOpenProjectsSmart()) {
                    action()
                }
            }
        }
    }
}
