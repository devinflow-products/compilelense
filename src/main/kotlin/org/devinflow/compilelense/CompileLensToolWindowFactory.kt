package org.devinflow.compilelense

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.devinflow.compilelense.scan.CompileLensScanService
import org.devinflow.compilelense.ui.CompileLensDashboardPanel
import javax.swing.Icon

/**
 * Hosts the live dashboard in the CompileLens tool window on the right stripe.
 * The window stays docked until the user opens it; we do not auto-switch to Float mode on startup.
 *
 * Also keeps the stripe icon in sync with the dashboard state:
 *  - green check  when the workspace has no compilation errors;
 *  - red badge    when at least one uncompiled class is on the dashboard.
 */
class CompileLensToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val dashboard = CompileLensDashboardPanel(project)
        val content = ContentFactory.getInstance().createContent(dashboard, null, false).apply {
            isCloseable = false
            setDisposer(dashboard)
        }
        toolWindow.contentManager.addContent(content)

        installStatusIconListener(project, toolWindow, dashboard)
    }

    private fun installStatusIconListener(
        project: Project,
        toolWindow: ToolWindow,
        parentDisposable: com.intellij.openapi.Disposable,
    ) {
        val scanService = CompileLensScanService.getInstance(project)

        applyStatusIcon(toolWindow, scanService.getSnapshot().totalCount)

        val subscription = scanService.addListener { snapshot ->
            val total = snapshot.totalCount
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                applyStatusIcon(toolWindow, total)
            }
        }
        Disposer.register(parentDisposable, subscription)
    }

    private fun applyStatusIcon(toolWindow: ToolWindow, errorCount: Int) {
        val icon: Icon = if (errorCount > 0) CompileLensIcons.ToolWindowError
                        else CompileLensIcons.ToolWindowClean
        toolWindow.setIcon(icon)
    }
}
