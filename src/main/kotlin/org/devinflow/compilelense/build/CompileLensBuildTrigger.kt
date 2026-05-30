package org.devinflow.compilelense.build

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.devinflow.compilelense.scan.CompileLensDebugLog
import org.devinflow.compilelense.scan.CompileLensScanCoordinator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Build > Rebuild Project on every open, indexed project, then signals completion.
 */
internal object CompileLensBuildTrigger {

    fun rebuildOpenProjects(trigger: Project, onAllFinished: () -> Unit) {
        val projects = CompileLensScanCoordinator.openProjects()
            .filter { !DumbService.isDumb(it) && canCompileProject(it) }

        if (projects.isEmpty()) {
            CompileLensDebugLog.info(trigger, "rebuild skipped: no compilable open projects")
            onAllFinished()
            return
        }

        CompileLensDebugLog.info(trigger, "rebuild started: ${projects.map { it.name }}")

        val remaining = AtomicInteger(projects.size)
        for (project in projects) {
            rebuildProject(project) { aborted, errors, warnings ->
                CompileLensDebugLog.info(
                    trigger,
                    "rebuild finished: ${project.name} aborted=$aborted errors=$errors warnings=$warnings",
                )
                if (remaining.decrementAndGet() == 0) {
                    AppExecutorUtil.getAppExecutorService().execute(onAllFinished)
                }
            }
        }
    }

    /**
     * Returns true only when [project] has at least one module with source roots
     * AND every such module has an SDK assigned.
     *
     * `CompilerManager.rebuild()` internally runs `CompileDriver.validateJdks()`, which
     * calls `Logger.error("The SDK is not specified for module …")` whenever any
     * compilable module lacks a JDK. The JetBrains plugin verifier opens synthetic
     * projects (e.g. `gradle-simple`) without SDKs, so we silently skip the rebuild
     * for any project that would fail JDK validation.
     */
    private fun canCompileProject(project: Project): Boolean {
        val compilableModules = ModuleManager.getInstance(project).modules
            .asSequence()
            .filter { !it.isDisposed }
            .filter { ModuleRootManager.getInstance(it).getSourceRoots(false).isNotEmpty() }
            .toList()
        if (compilableModules.isEmpty()) return false
        return compilableModules.all { ModuleRootManager.getInstance(it).sdk != null }
    }

    private fun rebuildProject(
        project: Project,
        onFinished: (aborted: Boolean, errors: Int, warnings: Int) -> Unit,
    ) {
        if (project.isDisposed) {
            onFinished(true, 0, 0)
            return
        }

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) {
                onFinished(true, 0, 0)
                return@invokeLater
            }
            CompilerManager.getInstance(project).rebuild(
                object : CompileStatusNotification {
                    override fun finished(
                        aborted: Boolean,
                        errors: Int,
                        warnings: Int,
                        compileContext: com.intellij.openapi.compiler.CompileContext,
                    ) {
                        CompileLensBuildCapture.captureFromCompileContext(
                            project,
                            aborted,
                            errors,
                            compileContext,
                        ) {
                            onFinished(aborted, errors, warnings)
                        }
                    }
                },
            )
        }, ModalityState.defaultModalityState())
    }
}
