package org.devinflow.compilelense.build

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.devinflow.compilelense.model.UncompiledIssue
import org.devinflow.compilelense.scan.BuildMessageSnapshot
import org.devinflow.compilelense.scan.CompileLensBuildErrorCache
import org.devinflow.compilelense.scan.CompileLensDebugLog
import org.devinflow.compilelense.scan.CompileLensScanService
import org.devinflow.compilelense.scan.CompilerMessageScanner
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures javac errors from any finished compile (plugin rebuild or user Build action).
 */
internal object CompileLensBuildCapture {

    fun captureFromCompileContext(
        project: Project,
        aborted: Boolean,
        errors: Int,
        compileContext: CompileContext,
        onDone: (() -> Unit)? = null,
    ) {
        val snapshots = if (!aborted && errors > 0) {
            CompilerMessageScanner.snapshotErrors(compileContext)
        } else {
            emptyList()
        }
        captureAsync(project, aborted, errors, snapshots, onDone)
    }

    /**
     * Merges javac errors into [CompileLensBuildErrorCache] and schedules a follow-up dashboard scan.
     *
     * Resolution of [BuildMessageSnapshot]s into [UncompiledIssue]s touches several index-backed
     * APIs ([com.intellij.openapi.roots.ProjectFileIndex.isInSourceContent],
     * [com.intellij.openapi.module.ModuleUtil.findModuleForFile],
     * [com.intellij.psi.search.FileTypeIndex.getFiles]). Right after a rebuild, the IDE often
     * briefly re-enters dumb mode while it refreshes indexes for the freshly produced class files
     * — a plain [ReadAction.run] then fails with `IndexNotReadyException`, the merge is silently
     * dropped on the floor, and the dashboard shows only live daemon errors for the file currently
     * open in the editor (every other file's javac errors disappear). This is the
     * "sometimes works, sometimes doesn't" inconsistency.
     *
     * We therefore run the resolution inside `ReadAction.nonBlocking(...).inSmartMode(project)`,
     * which waits for smart mode before taking the read lock — exactly the same protection
     * [CompileLensScanService.runScan] already applies on the main scan path.
     *
     * `onDone` (used by `rebuildOpenProjects` to count down its `AtomicInteger` latch and trigger
     * a final refreshScan) and the follow-up dashboard `scheduleScan(immediate = true)` are
     * funnelled through [finishLatch] so they run exactly once on every terminal outcome of the
     * promise — success, failure, or cancellation (project closed mid-rebuild). Without this,
     * a cancellation would leave `remaining > 0` forever and the rebuild orchestration would
     * hang silently.
     */
    fun captureAsync(
        project: Project,
        aborted: Boolean,
        errors: Int,
        snapshots: List<BuildMessageSnapshot>,
        onDone: (() -> Unit)? = null,
    ) {
        val finishLatch = AtomicBoolean(false)
        val invokeFinish = Runnable {
            if (!finishLatch.compareAndSet(false, true)) return@Runnable
            onDone?.invoke()
            if (!project.isDisposed && !aborted) {
                CompileLensScanService.getInstance(project).scheduleScan(immediate = true)
            }
        }
        val invokeFinishOnEdt = Runnable {
            ApplicationManager.getApplication().invokeLater(invokeFinish, ModalityState.defaultModalityState())
        }

        if (project.isDisposed || aborted) {
            invokeFinishOnEdt.run()
            return
        }
        if (errors == 0) {
            CompileLensBuildErrorCache.clear(project)
            invokeFinishOnEdt.run()
            return
        }
        if (snapshots.isEmpty()) {
            invokeFinishOnEdt.run()
            return
        }

        val promise = ReadAction.nonBlocking<List<UncompiledIssue>> {
            if (project.isDisposed) emptyList()
            else CompilerMessageScanner.collectMessages(project, snapshots)
        }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { collected ->
                try {
                    if (collected.isNotEmpty()) {
                        CompileLensBuildErrorCache.merge(project, collected)
                        CompileLensDebugLog.info(
                            project,
                            "build: captured ${collected.size}/${snapshots.size} compiler error(s) from ${project.name}",
                        )
                        collected.forEach { issue ->
                            CompileLensDebugLog.info(
                                project,
                                "  build issue -> ${issue.fileName}:${issue.lineNumber} ${issue.issueDetail}",
                            )
                        }
                    } else {
                        CompileLensDebugLog.info(
                            project,
                            "build: 0/${snapshots.size} compiler error(s) resolved into dashboard issues for ${project.name}",
                        )
                    }
                } catch (e: Exception) {
                    CompileLensDebugLog.warn(project, "build: failed to merge captured errors", e)
                } finally {
                    invokeFinish.run()
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())

        promise.onError { error ->
            if (error !is ProcessCanceledException && error !is CancellationException) {
                CompileLensDebugLog.warn(project, "build: capture promise rejected", error)
            }
            invokeFinishOnEdt.run()
        }
    }

    fun registerCompilationListener(project: Project) {
        CompilerManager.getInstance(project).addCompilationStatusListener(
            object : CompilationStatusListener {
                override fun compilationFinished(
                    aborted: Boolean,
                    errors: Int,
                    warnings: Int,
                    compileContext: CompileContext,
                ) {
                    captureFromCompileContext(project, aborted, errors, compileContext)
                }
            },
        )
    }
}
