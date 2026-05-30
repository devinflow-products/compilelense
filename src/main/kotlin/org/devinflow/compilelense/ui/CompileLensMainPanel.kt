package org.devinflow.compilelense.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.model.DashboardSnapshot
import org.devinflow.compilelense.model.UncompiledClassRow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
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
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        horizontalScrollBar.unitIncrement = JBUI.scale(16)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = issuesTable.onViewportResized()
        })
    }

    private val sourceRootsHintBanner: JPanel = buildSourceRootsHintBanner().apply { isVisible = false }
    private val northContainer: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(buildHeader())
        add(sourceRootsHintBanner)
    }

    private var allClasses: List<UncompiledClassRow> = emptyList()
    private val filterDebounceTimer = Timer(FILTER_DEBOUNCE_MS) { applyFiltersInternal() }

    init {
        background = CompileLensUi.cardBackground
        minimumSize = Dimension(JBUI.scale(220), JBUI.scale(200))
        add(northContainer, BorderLayout.NORTH)
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

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            searchField.apply {
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            sortCombo.apply {
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            }
            add(searchField)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(sortCombo)
        }
        add(controls, BorderLayout.CENTER)
    }

    /**
     * Shown when the latest scan found zero Java source roots across every open project —
     * i.e. the folder was opened without being imported as a Maven/Gradle/JPS project, so
     * no Java file passes [com.intellij.openapi.roots.ProjectFileIndex.isInSourceContent]
     * and the dashboard would otherwise stay silently empty.
     */
    private fun buildSourceRootsHintBanner(): JPanel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        isOpaque = true
        background = CompileLensUi.cardBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, CompileLensUi.accentBlue),
            JBUI.Borders.empty(10, 13, 10, 16),
        )
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        add(JBLabel(AllIcons.General.BalloonInformation), BorderLayout.WEST)

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("No Java source roots detected").apply {
                font = CompileLensUi.boldFont
                foreground = CompileLensUi.primaryText
                alignmentX = LEFT_ALIGNMENT
            })
            add(JBLabel(
                "<html>Import this folder as a Maven/Gradle project, " +
                    "or right-click a directory and choose <b>Mark Directory as &rarr; Sources Root</b>.</html>",
            ).apply {
                font = CompileLensUi.smallFont
                foreground = CompileLensUi.mutedText
                alignmentX = LEFT_ALIGNMENT
            })
        }
        add(textPanel, BorderLayout.CENTER)
    }

    fun update(snapshot: DashboardSnapshot) {
        allClasses = snapshot.uncompiledClasses
        val showHint = !snapshot.hasJavaSourceRoots
        if (sourceRootsHintBanner.isVisible != showHint) {
            sourceRootsHintBanner.isVisible = showHint
            northContainer.revalidate()
            northContainer.repaint()
        }
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
                    it.moduleName.lowercase().contains(query) ||
                    it.projectName.lowercase().contains(query)
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
