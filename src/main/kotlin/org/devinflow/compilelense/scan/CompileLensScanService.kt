package org.devinflow.compilelense.scan

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsListener
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
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
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class CompileLensScanService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val snapshotRef = AtomicReference(DashboardSnapshot(emptyList(), Instant.EPOCH))
    private val listeners = CopyOnWriteArrayList<(DashboardSnapshot) -> Unit>()
    private val scanInProgress = AtomicBoolean(false)
    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevantChange(it) }) scheduleScan()
            }
        })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = scheduleScan()
        })
        connection.subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(@Suppress("UNUSED_PARAMETER") addedTo: Project, modules: List<Module>) =
                scheduleScan(immediate = true)

            override fun moduleRemoved(@Suppress("UNUSED_PARAMETER") removedFrom: Project, module: Module) =
                scheduleScan(immediate = true)
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbModeListener {
            override fun exitDumbMode() = scheduleScan(immediate = true)
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
            override fun daemonFinished() = scheduleScan()
        })
        connection.subscribe(ProblemsListener.TOPIC, object : ProblemsListener {
            override fun problemAppeared(problem: Problem) = scheduleScan(immediate = true)
            override fun problemDisappeared(problem: Problem) {
                problem.filePath()?.let { CompileLensBuildErrorCache.removeFile(project, it) }
                scheduleScan(immediate = true)
            }
            override fun problemUpdated(problem: Problem) = scheduleScan()
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (isJavaDocument(event.document)) scheduleScan(immediate = true)
                }
            },
            this,
        )

        scheduleScanWhenSmart()
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
        CompileLensScanCoordinator.rescanWithBuild(project, onFinished)
    }

    internal fun scheduleScan(immediate: Boolean = false) {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        val delay = if (immediate) 0 else SCAN_DEBOUNCE_MS
        alarm.addRequest({ runScan() }, delay)
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

    private fun scheduleScanWhenSmart() {
        if (project.isDisposed) return
        CompileLensScanCoordinator.runWhenAllProjectsSmart(project) {
            scheduleScan()
        }
    }

    private fun runScan() {
        if (project.isDisposed) return
        if (!scanInProgress.compareAndSet(false, true)) {
            scheduleScan()
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

        CompileLensDebugLog.info(project, "scan scheduled (ReadAction.nonBlocking)")

        val previouslyTrackedPaths = snapshotRef.get().issues.map { it.virtualFilePath }.toSet()

        ReadAction.nonBlocking<List<UncompiledIssue>> {
            try {
                UncompiledClassScanner.scan(project, previouslyTrackedPaths)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CompileLensDebugLog.warn(project, "scan failed", e)
                emptyList()
            }
        }
            .coalesceBy(CompileLensScanCoordinator)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { issues ->
                try {
                    publishSnapshot(issues)
                } finally {
                    scanInProgress.set(false)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun publishSnapshot(issues: List<UncompiledIssue>) {
        if (project.isDisposed) return
        if (DumbService.isDumb(project)) {
            scheduleScanWhenSmart()
            return
        }
        val snapshot = DashboardSnapshot(issues, Instant.now())
        snapshotRef.set(snapshot)
        CompileLensDebugLog.info(
            project,
            "snapshot published: classes=${snapshot.totalCount} listeners=${listeners.size}",
        )
        listeners.forEach { it(snapshot) }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        listeners.clear()
        scanInProgress.set(false)
        CompileLensBuildErrorCache.clear(project)
    }

    private fun Problem.filePath(): String? =
        when (this) {
            is FileProblem -> file?.path
            else -> null
        }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 300

        fun getInstance(project: Project): CompileLensScanService =
            project.getService(CompileLensScanService::class.java)
    }
}
