package org.devinflow.compilelense

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import org.devinflow.compilelense.scan.CompileLensScanCoordinator
import org.devinflow.compilelense.scan.CompileLensScanService
import org.devinflow.compilelense.scan.CompilationErrorService

/**
 * After indexing completes, rebuilds every open project once and publishes a full dashboard snapshot.
 */
class CompileLensStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        CompileLensScanService.getInstance(project)
        CompilationErrorService.getInstance(project)

        AppExecutorUtil.getAppExecutorService().execute {
            CompileLensScanCoordinator.runWhenAllProjectsSmart(project) {
                if (!project.isDisposed) {
                    CompileLensScanCoordinator.runStartupWithBuild(project)
                }
            }
        }
    }
}
