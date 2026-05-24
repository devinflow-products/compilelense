package org.devinflow.compilelense

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.devinflow.compilelense.ui.CompileLensDashboardPanel

/**
 * Hosts the live dashboard in the CompileLens tool window on the right stripe.
 * The window stays docked until the user opens it; we do not auto-switch to Float mode on startup.
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
    }
}
