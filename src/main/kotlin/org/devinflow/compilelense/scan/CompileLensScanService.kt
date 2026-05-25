package org.devinflow.compilelense.scan

import org.devinflow.compilelense.build.CompileLensBuildCapture
import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsListener
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbService.DumbModeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import org.devinflow.compilelense.model.DashboardSnapshot
import org.devinflow.compilelense.model.UncompiledIssue
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class CompileLensScanService(private val project: Project) : Disposable {

    private val errorService: CompilationErrorService
        get() = CompilationErrorService.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val snapshotRef = AtomicReference(DashboardSnapshot(emptyList(), Instant.EPOCH))
    private val listeners = CopyOnWriteArrayList<(DashboardSnapshot) -> Unit>()
    private val scanInProgress = AtomicBoolean(false)
    private val pendingFullWorkspaceScan = AtomicBoolean(false)
    private val startupBaselineCompleted = AtomicBoolean(false)

    /**
     * Timestamp of the last daemon restart initiated by CompileLens itself. A self-induced
     * `daemonFinished` event arriving within [SELF_DAEMON_RESTART_QUIET_MS] is ignored so
     * that the scan -> daemon restart -> daemonFinished -> scan cycle does not feed itself.
     */
    private val lastSelfDaemonRestartAt = AtomicLong(0L)

    init {
        CompileLensBuildCapture.registerCompilationListener(project)
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                var structuralChange = false
                val changedJavaFiles = LinkedHashSet<VirtualFile>()
                for (event in events) {
                    if (!isRelevantChange(event)) continue
                    val file = event.file ?: continue
                    if (file.isJavaFile()) {
                        file.path.let { CompileLensAnalysisSession.invalidateFileInWorkspace(it) }
                        changedJavaFiles += file
                    }
                    if (file.path.endsWith("pom.xml") ||
                        file.path.endsWith(".gradle") ||
                        file.path.endsWith(".gradle.kts")
                    ) {
                        structuralChange = true
                    }
                }
                if (structuralChange) {
                    scheduleFullWorkspaceRescan("project structure changed")
                }
                for (file in changedJavaFiles) {
                    CompileLensWorkspaceAnalysis.scheduleDaemonRestartForVirtualFile(
                        file,
                        "CompileLens file saved",
                    )
                }
            }
        })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = scheduleFullWorkspaceRescan("module roots changed")
        })
        connection.subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(@Suppress("UNUSED_PARAMETER") addedTo: Project, modules: List<Module>) =
                scheduleFullWorkspaceRescan("module added")

            override fun moduleRemoved(@Suppress("UNUSED_PARAMETER") removedFrom: Project, module: Module) =
                scheduleFullWorkspaceRescan("module removed")
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbModeListener {
            override fun exitDumbMode() = scheduleFullWorkspaceRescan("indexing finished")
        })
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (file.isJavaFile()) scheduleScan(immediate = true)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file.isJavaFile()) scheduleScan()
                }
            },
        )
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() {
                if (isWithinSelfDaemonRestartQuietWindow()) return
                scheduleLiveUpdateScan("daemon finished")
            }
        })
        connection.subscribe(ProblemsListener.TOPIC, object : ProblemsListener {
            override fun problemAppeared(problem: Problem) {
                if (isWithinSelfDaemonRestartQuietWindow()) return
                scheduleLiveUpdateScan("problem appeared")
            }
            override fun problemDisappeared(problem: Problem) {
                problem.filePath()?.let { path ->
                    CompileLensAnalysisSession.invalidateFileInWorkspace(path)
                }
                if (isWithinSelfDaemonRestartQuietWindow()) return
                scheduleLiveUpdateScan("problem disappeared")
            }
            override fun problemUpdated(problem: Problem) {
                if (isWithinSelfDaemonRestartQuietWindow()) return
                scheduleLiveUpdateScan("problem updated")
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (project.isDisposed) return
                    val document = event.document
                    // documentChanged may fire on a background thread (e.g. inside a non-blocking
                    // read action). PsiDocumentManager.performWhenAllCommitted requires EDT, so
                    // hop there before touching PSI / committing state.
                    val app = ApplicationManager.getApplication()
                    val runOnEdt = Runnable {
                        if (project.isDisposed) return@Runnable
                        if (!isJavaDocument(document)) return@Runnable
                        val documentManager = PsiDocumentManager.getInstance(project)
                        documentManager.performWhenAllCommitted {
                            if (project.isDisposed) return@performWhenAllCommitted
                            val psiFile = documentManager.getPsiFile(document) as? PsiJavaFile
                                ?: return@performWhenAllCommitted
                            val virtualFile = psiFile.virtualFile ?: return@performWhenAllCommitted
                            CompileLensAnalysisSession.invalidateFileInWorkspace(virtualFile.path)
                            CompileLensWorkspaceAnalysis.scheduleDaemonRestarts(
                                project,
                                listOf(virtualFile),
                                "CompileLens document edit",
                            )
                            // Also enqueue a scan so the dashboard updates promptly even
                            // if the post-scan self-daemon-restart quiet window swallows
                            // the next `daemonFinished` event. Debounce gives the daemon
                            // enough time to complete its analysis pass first.
                            scheduleScan(delayMs = DOC_CHANGE_SCAN_DEBOUNCE_MS)
                        }
                    }
                    if (app.isDispatchThread) {
                        runOnEdt.run()
                    } else {
                        app.invokeLater(runOnEdt, ModalityState.defaultModalityState()) {
                            project.isDisposed
                        }
                    }
                }
            },
            this,
        )
    }

    /**
     * Startup baseline: clear central state, scan every Java source, publish a complete dashboard snapshot.
     */
    fun runStartupBaselineScan() {
        if (project.isDisposed || !startupBaselineCompleted.compareAndSet(false, true)) return
        CompileLensDebugLog.info(project, "startup baseline scan requested")
        pendingFullWorkspaceScan.set(true)
        scheduleScan(immediate = true, fullWorkspace = true)
    }

    fun scheduleFullWorkspaceRescan(reason: String) {
        if (project.isDisposed) return
        CompileLensDebugLog.info(project, "full workspace rescan: $reason")
        CompileLensScanCoordinator.requestWorkspaceRebuild(project, reason)
    }

    fun addListener(listener: (DashboardSnapshot) -> Unit): Disposable {
        listeners += listener
        listener(snapshotRef.get())
        return Disposable { listeners -= listener }
    }

    fun getSnapshot(): DashboardSnapshot = snapshotRef.get()

    fun refresh() {
        CompileLensScanCoordinator.refreshAll(project, restartDaemon = true)
    }

    fun rescanWithBuild(onFinished: (() -> Unit)? = null) {
        CompileLensScanCoordinator.requestWorkspaceRebuild(project, "manual rescan", onFinished)
    }

    internal fun scheduleScan(
        immediate: Boolean = false,
        fullWorkspace: Boolean = false,
        delayMs: Int? = null,
    ) {
        if (project.isDisposed) return
        if (fullWorkspace) {
            pendingFullWorkspaceScan.set(true)
        } else if (!CompileLensScanCoordinator.isWorkspaceSnapshotReady()) {
            pendingFullWorkspaceScan.set(true)
        }
        alarm.cancelAllRequests()
        val delay = delayMs ?: if (immediate) 0 else SCAN_DEBOUNCE_MS
        alarm.addRequest({ runScan() }, delay)
    }

    private fun scheduleLiveUpdateScan(reason: String) {
        if (project.isDisposed) return
        if (!CompileLensScanCoordinator.isWorkspaceSnapshotReady()) return
        CompileLensDebugLog.info(project, "live update scan: $reason")
        scheduleScan(immediate = true)
    }

    private fun isJavaDocument(document: Document): Boolean {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return false
        return psiFile is PsiJavaFile
    }

    private fun isRelevantChange(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        return file.isJavaFile() ||
            file.path.endsWith("pom.xml") ||
            file.path.endsWith(".gradle") ||
            file.path.endsWith(".gradle.kts")
    }

    private fun VirtualFile.isJavaFile(): Boolean =
        extension?.equals("java", ignoreCase = true) == true

    private fun scheduleScanWhenSmart(fullWorkspace: Boolean = false) {
        if (project.isDisposed) return
        CompileLensScanCoordinator.runWhenAllProjectsSmart(project) {
            scheduleScan(fullWorkspace = fullWorkspace)
        }
    }

    private fun runScan() {
        if (project.isDisposed) return
        if (!scanInProgress.compareAndSet(false, true)) {
            scheduleScan(immediate = true)
            return
        }
        val waitingOn = CompileLensScanCoordinator.dumbProjects()
        if (waitingOn.isNotEmpty()) {
            scanInProgress.set(false)
            CompileLensDebugLog.info(
                project,
                "scan deferred: waiting for smart mode on ${waitingOn.map { it.name }}",
            )
            scheduleScanWhenSmart()
            return
        }

        val mode = if (pendingFullWorkspaceScan.compareAndSet(true, false)) {
            CompileLensScanCoordinator.clearAllErrorServices()
            CompileLensScanMode.FULL_WORKSPACE
        } else {
            CompileLensScanMode.INCREMENTAL
        }
        CompileLensDebugLog.info(project, "scan scheduled (ReadAction.nonBlocking) mode=$mode")

        val previouslyTrackedPaths = errorService.trackedFilePaths()

        ReadAction.nonBlocking<CompileLensScanResult> {
            try {
                UncompiledClassScanner.scan(project, previouslyTrackedPaths, mode)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CompileLensDebugLog.warn(project, "scan failed", e)
                CompileLensScanResult(emptyList(), emptySet(), mode == CompileLensScanMode.INCREMENTAL)
            }
        }
            .inSmartMode(project)
            .coalesceBy(CompileLensScanCoordinator)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { scanResult ->
                try {
                    publishSnapshot(scanResult)
                    scheduleDaemonRestarts(scanResult)
                } finally {
                    scanInProgress.set(false)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun scheduleDaemonRestarts(scanResult: CompileLensScanResult) {
        val now = System.currentTimeMillis()
        for ((projectRef, files) in scanResult.daemonRestartsByProject) {
            if (files.isEmpty() || projectRef.project.isDisposed) continue
            getInstance(projectRef.project).markSelfDaemonRestart(now)
            CompileLensWorkspaceAnalysis.scheduleDaemonRestarts(
                projectRef.project,
                files,
                "CompileLens workspace analysis",
            )
        }
    }

    internal fun markSelfDaemonRestart(timestampMs: Long = System.currentTimeMillis()) {
        lastSelfDaemonRestartAt.set(timestampMs)
    }

    private fun isWithinSelfDaemonRestartQuietWindow(): Boolean {
        val last = lastSelfDaemonRestartAt.get()
        if (last == 0L) return false
        return System.currentTimeMillis() - last < SELF_DAEMON_RESTART_QUIET_MS
    }

    private fun publishSnapshot(scanResult: CompileLensScanResult) {
        if (project.isDisposed) return
        if (DumbService.isDumb(project)) {
            scheduleScanWhenSmart()
            return
        }
        if (scanResult.incremental && !CompileLensScanCoordinator.isWorkspaceSnapshotReady()) {
            pendingFullWorkspaceScan.set(true)
            scheduleScan(immediate = true, fullWorkspace = true)
            return
        }

        val mergedIssues = CompileLensScanCoordinator.mergeScanIntoWorkspace(
            scannedFilePaths = scanResult.scannedFilePaths,
            scannedIssues = scanResult.issues,
            fullReplace = !scanResult.incremental,
        )
        CompileLensScanCoordinator.syncWorkspaceIssues(mergedIssues)

        if (!scanResult.incremental) {
            CompileLensScanCoordinator.markWorkspaceSnapshotReady()
        }

        val snapshot = DashboardSnapshot(mergedIssues, Instant.now())
        snapshotRef.set(snapshot)
        CompileLensDebugLog.info(
            project,
            "snapshot published: mode=${if (scanResult.incremental) "incremental" else "full"} " +
                "classes=${snapshot.totalCount} projects=${CompileLensScanCoordinator.openProjects().map { it.name }}",
        )
        notifyAllDashboardListeners(snapshot)
    }

    private fun notifyAllDashboardListeners(snapshot: DashboardSnapshot) {
        for (openProject in CompileLensScanCoordinator.openProjects()) {
            if (openProject.isDisposed) continue
            CompileLensScanService.getInstance(openProject).notifyListeners(snapshot)
        }
    }

    internal fun notifyListeners(snapshot: DashboardSnapshot) {
        listeners.forEach { it(snapshot) }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        listeners.clear()
        scanInProgress.set(false)
        pendingFullWorkspaceScan.set(false)
        startupBaselineCompleted.set(false)
        CompileLensScanCoordinator.resetWorkspaceSnapshotReady()
        errorService.clear()
        CompileLensAnalysisSession.clear(project)
    }

    private fun Problem.filePath(): String? =
        when (this) {
            is FileProblem -> file?.path
            else -> null
        }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 500

        /**
         * Debounce applied when a document edit triggers a scan directly. Long enough
         * that the IntelliJ daemon has typically finished its highlighting pass on the
         * edited file before we read its results, but short enough that the dashboard
         * still feels responsive.
         */
        private const val DOC_CHANGE_SCAN_DEBOUNCE_MS = 1200

        /**
         * Quiet window after a CompileLens-initiated daemon restart during which any
         * incoming `daemonFinished` event is treated as our own echo and ignored.
         * Must be longer than the typical daemon turnaround on small projects (~350ms)
         * to break the self-feeding scan loop.
         */
        private const val SELF_DAEMON_RESTART_QUIET_MS = 1500L

        fun getInstance(project: Project): CompileLensScanService =
            project.getService(CompileLensScanService::class.java)
    }
}
