package org.devinflow.compilelense.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.model.DashboardSnapshot
import org.devinflow.compilelense.scan.CompileLensScanCoordinator
import org.devinflow.compilelense.scan.CompileLensScanService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JSplitPane

class CompileLensDashboardPanel(private val project: Project) : JBPanel<CompileLensDashboardPanel>(BorderLayout()), Disposable {

    private val scanService = CompileLensScanService.getInstance(project)
    private val mainPanel = CompileLensMainPanel(project)
    private lateinit var sidebarPanel: CompileLensSidebarPanel
    private lateinit var splitter: JSplitPane
    private var listenerDisposable: com.intellij.openapi.Disposable? = null
    @Volatile
    private var rescanInProgress = false

    init {
        background = CompileLensUi.sidebarBackground
        border = JBUI.Borders.empty()

        sidebarPanel = CompileLensSidebarPanel(
            project = project,
            onFilterChanged = ::applySidebarFilters,
            onViewAllClicked = ::resetSidebarFilters,
            onRescanClicked = ::triggerRescan,
        )

        splitter = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, mainPanel).apply {
            resizeWeight = 0.3
            isContinuousLayout = true
            isOneTouchExpandable = false
            setDividerSize(JBUI.scale(4))
            leftComponent.minimumSize = Dimension(0, 0)
            rightComponent.minimumSize = Dimension(0, 0)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) = updateDividerLocation()
            })
        }
        add(splitter, BorderLayout.CENTER)

        listenerDisposable = scanService.addListener { snapshot ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    render(snapshot)
                }
            }
        }
    }

    override fun addNotify() {
        super.addNotify()
        render(scanService.getSnapshot())
    }

    private fun triggerRescan() {
        if (project.isDisposed || rescanInProgress) return
        if (!CompileLensScanCoordinator.allOpenProjectsSmart()) {
            CompileLensScanCoordinator.runWhenAllProjectsSmart(project) { triggerRescan() }
            return
        }
        rescanInProgress = true
        sidebarPanel.setRescanInProgress(true)
        scanService.rescanWithBuild {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                rescanInProgress = false
                if (!project.isDisposed) {
                    sidebarPanel.setRescanInProgress(false)
                    updateDividerLocation(force = true)
                }
            }
        }
    }

    override fun doLayout() {
        super.doLayout()
        if (!project.isDisposed) {
            updateDividerLocation(force = false)
        }
    }

    private fun updateDividerLocation(force: Boolean = false) {
        if (splitter.width <= 0 || splitter.height <= 0) return

        val useVerticalLayout = splitter.width < JBUI.scale(760)
        if (useVerticalLayout) {
            if (splitter.orientation != JSplitPane.VERTICAL_SPLIT) {
                splitter.orientation = JSplitPane.VERTICAL_SPLIT
            }
            val minTop = JBUI.scale(190)
            val maxTop = (splitter.height - JBUI.scale(220)).coerceAtLeast(minTop)
            val preferredTop = sidebarPanel.preferredSize.height.coerceAtLeast(minTop)
            val targetTop = preferredTop.coerceIn(minTop, maxTop)
            if (force || splitter.dividerLocation <= 0) {
                splitter.dividerLocation = targetTop
            }
            return
        }

        if (splitter.orientation != JSplitPane.HORIZONTAL_SPLIT) {
            splitter.orientation = JSplitPane.HORIZONTAL_SPLIT
        }

        val minSidebar = JBUI.scale(260)
        val minMain = JBUI.scale(220)
        val maxSidebar = (splitter.width - minMain).coerceAtLeast(minSidebar)
        val preferredSidebar = sidebarPanel.preferredSize.width.coerceAtLeast(minSidebar)
        val targetSidebar = preferredSidebar.coerceIn(minSidebar, maxSidebar)
        if (force || splitter.dividerLocation <= 0) {
            splitter.dividerLocation = targetSidebar
        }
    }

    private fun render(snapshot: DashboardSnapshot) {
        val sidebarResized = sidebarPanel.update(snapshot)
        mainPanel.update(snapshot)
        applySidebarFilters()
        updateDividerLocation(force = sidebarResized || splitter.dividerLocation <= 0)
    }

    private fun applySidebarFilters() {
        mainPanel.applyFilters(
            moduleFilter = sidebarPanel.selectedModule(),
            showOnlyOpenFiles = sidebarPanel.showOnlyOpenFiles(),
        )
    }

    private fun resetSidebarFilters() {
        sidebarPanel.moduleFilterCombo.selectedItem = "All Modules"
        sidebarPanel.showOnlyOpenFilesCheck.isSelected = false
        applySidebarFilters()
    }

    override fun dispose() {
        listenerDisposable?.let { Disposer.dispose(it) }
        listenerDisposable = null
        sidebarPanel.dispose()
        mainPanel.dispose()
    }
}
