package org.devinflow.compilelense.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.model.DashboardSnapshot
import org.devinflow.compilelense.model.UncompiledClassRow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

internal class CompileLensMainPanel(
    private val project: Project,
) : JBPanel<CompileLensMainPanel>(BorderLayout()) {

    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search classes..."
        preferredSize = Dimension(JBUI.scale(220), preferredSize.height)
        minimumSize = Dimension(JBUI.scale(120), preferredSize.height)
    }
    private val sortCombo = JComboBox(arrayOf("Sort by: Folder", "Sort by: Class"))
    private val issuesTable = CompileLensIssuesTable(project)
    private val tableScrollPane = JScrollPane(issuesTable).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, CompileLensUi.borderColor)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = issuesTable.onViewportResized()
        })
    }

    private var allClasses: List<UncompiledClassRow> = emptyList()
    private val filterDebounceTimer = Timer(FILTER_DEBOUNCE_MS) { applyFiltersInternal() }

    init {
        background = CompileLensUi.cardBackground
        minimumSize = Dimension(JBUI.scale(220), JBUI.scale(200))
        add(buildHeader(), BorderLayout.NORTH)
        add(tableScrollPane, BorderLayout.CENTER)

        searchField.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = scheduleFilterUpdate()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = scheduleFilterUpdate()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = scheduleFilterUpdate()
        })
        sortCombo.addActionListener { applyFiltersInternal() }
        filterDebounceTimer.isRepeats = false
    }

    private fun buildHeader(): JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(10, 16, 8, 16)

        val controls = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(searchField, BorderLayout.CENTER)
            add(sortCombo, BorderLayout.EAST)
        }
        add(controls, BorderLayout.CENTER)
    }

    fun update(snapshot: DashboardSnapshot) {
        allClasses = snapshot.uncompiledClasses
        applyFiltersInternal()
    }

    fun applyFilters(
        moduleFilter: String? = null,
        showOnlyOpenFiles: Boolean = false,
    ) {
        pendingModuleFilter = moduleFilter
        pendingShowOnlyOpenFiles = showOnlyOpenFiles
        applyFiltersInternal()
    }

    private var pendingModuleFilter: String? = null
    private var pendingShowOnlyOpenFiles: Boolean = false

    private fun scheduleFilterUpdate() {
        filterDebounceTimer.restart()
    }

    private fun applyFiltersInternal() {
        var filtered = allClasses

        pendingModuleFilter?.let { module -> filtered = filtered.filter { it.moduleName == module } }

        if (pendingShowOnlyOpenFiles) {
            val openPaths = FileEditorManager.getInstance(project).openFiles.map { it.path }.toSet()
            filtered = filtered.filter { it.virtualFilePath in openPaths }
        }

        val query = searchField.text.trim().lowercase()
        if (query.isNotEmpty()) {
            filtered = filtered.filter {
                it.fileName.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query) ||
                    it.className.lowercase().contains(query) ||
                    it.parentFolderName.lowercase().contains(query) ||
                    it.moduleName.lowercase().contains(query)
            }
        }

        filtered = when (sortCombo.selectedIndex) {
            1 -> filtered.sortedWith(compareBy({ it.fileName }, { it.lineNumbers.minOrNull() ?: 0 }))
            else -> filtered.sortedWith(
                compareBy({ it.parentFolderName }, { it.fileName }, { it.lineNumbers.minOrNull() ?: 0 }),
            )
        }

        issuesTable.setIssues(filtered)
    }

    fun dispose() {
        filterDebounceTimer.stop()
    }

    companion object {
        private const val FILTER_DEBOUNCE_MS = 120
    }
}
