package org.devinflow.compilelense

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import org.devinflow.compilelense.scan.CompileLensScanCoordinator

/**
 * Opens the CompileLens dashboard from the menu when the tool-window stripe is hidden or hard to find.
 */
class CompileLensOpenAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.show {
            toolWindow.setType(com.intellij.openapi.wm.ToolWindowType.FLOATING, null)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val ready = CompileLensScanCoordinator.allOpenProjectsSmart()
        e.presentation.isEnabledAndVisible = ready
        e.presentation.description = if (ready) {
            "Open the CompileLens uncompiled-classes dashboard"
        } else {
            "CompileLens is unavailable until indexing completes"
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "CompileLens"
    }
}
