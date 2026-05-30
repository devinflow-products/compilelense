package org.devinflow.compilelense.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.CompileLensIcons
import org.devinflow.compilelense.model.DashboardSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.Duration
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.DefaultComboBoxModel
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal class CompileLensSidebarPanel(
    private val project: Project,
    private val onFilterChanged: () -> Unit,
    private val onViewAllClicked: () -> Unit,
    private val onRescanClicked: () -> Unit,
) : JBPanel<CompileLensSidebarPanel>(BorderLayout()) {

    private val summaryStatusIcon = JBLabel(CompileLensIcons.SummaryClean).apply {
        alignmentX = CENTER_ALIGNMENT
    }
    private val summaryCountLabel = JBLabel("", SwingConstants.CENTER).apply {
        font = CompileLensUi.titleFont
        foreground = CompileLensUi.primaryText
    }
    private val summaryModulesLabel = JBLabel("", SwingConstants.CENTER).apply {
        foreground = CompileLensUi.mutedText
    }
    private val fixSuggestionLabel = JBLabel("", SwingConstants.CENTER).apply {
        foreground = CompileLensUi.mutedText
    }
    private val lastScanLabel = JBLabel("", SwingConstants.CENTER).apply {
        foreground = CompileLensUi.mutedText
    }
    private val moduleListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }
    val moduleFilterCombo = JComboBox(arrayOf("All Modules"))
    val showOnlyOpenFilesCheck = JBCheckBox("Show only open files")

    private var lastScan: Instant = Instant.now()
    private var fixSuggestionCount = 0
    private var suppressComboEvents = false
    private var pendingModuleList: List<String>? = null
    private var lastPreferredWidth = -1
    private val relativeTimeTimer = Timer(30_000) { updateLastScanLabel() }
    private val rescanButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Re-scan: rebuild all open projects and refresh the dashboard"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = CENTER_ALIGNMENT
        maximumSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        addActionListener { onRescanClicked() }
    }

    init {
        background = CompileLensUi.sidebarBackground
        minimumSize = Dimension(JBUI.scale(160), 0)
        preferredSize = Dimension(JBUI.scale(240), 0)
        border = BorderFactory.createMatteBorder(0, 0, 0, 1, CompileLensUi.borderColor)

        val scrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(CompileLensUi.panelInsets)
        }

        scrollContent.add(buildSummaryCard())
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(16)))
        scrollContent.add(buildSection("By Module", moduleListPanel))
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(16)))
        scrollContent.add(buildFiltersSection())
        scrollContent.add(Box.createVerticalGlue())

        add(
            JScrollPane(scrollContent).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                horizontalScrollBar.unitIncrement = JBUI.scale(16)
            },
            BorderLayout.CENTER,
        )

        moduleFilterCombo.addActionListener {
            if (!suppressComboEvents) onFilterChanged()
        }
        moduleFilterCombo.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = Unit

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {
                pendingModuleList?.let { modules ->
                    pendingModuleList = null
                    applyModuleFilterItems(modules)
                }
            }

            override fun popupMenuCanceled(e: PopupMenuEvent?) {
                pendingModuleList = null
            }
        })
        showOnlyOpenFilesCheck.addActionListener { onFilterChanged() }
        relativeTimeTimer.start()
    }

    private fun buildSummaryCard(): JPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = CompileLensUi.cardBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(CompileLensUi.borderColor),
            JBUI.Borders.empty(CompileLensUi.cardInsets),
        )
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

        add(summaryStatusIcon)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        summaryCountLabel.alignmentX = CENTER_ALIGNMENT
        add(summaryCountLabel)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        summaryModulesLabel.alignmentX = CENTER_ALIGNMENT
        add(summaryModulesLabel)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        fixSuggestionLabel.alignmentX = CENTER_ALIGNMENT
        add(fixSuggestionLabel)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        lastScanLabel.alignmentX = CENTER_ALIGNMENT
        add(lastScanLabel)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(rescanButton)
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(JButton("View All Classes").apply {
            alignmentX = CENTER_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            isContentAreaFilled = true
            background = CompileLensUi.accentBlue
            foreground = java.awt.Color.WHITE
            isFocusPainted = false
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            addActionListener { onViewAllClicked() }
        })
    }

    private fun buildSection(title: String, content: JPanel): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(JBLabel(title).apply {
            font = CompileLensUi.sectionFont
            foreground = CompileLensUi.primaryText
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = LEFT_ALIGNMENT
        })
        content.alignmentX = LEFT_ALIGNMENT
        add(content)
    }

    private fun buildFiltersSection(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(JBLabel("Filters").apply {
            font = CompileLensUi.sectionFont
            foreground = CompileLensUi.primaryText
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = LEFT_ALIGNMENT
        })
        moduleFilterCombo.maximumSize = Dimension(Int.MAX_VALUE, moduleFilterCombo.preferredSize.height)
        moduleFilterCombo.alignmentX = LEFT_ALIGNMENT
        add(moduleFilterCombo)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        showOnlyOpenFilesCheck.alignmentX = LEFT_ALIGNMENT
        add(showOnlyOpenFilesCheck)
    }

    fun update(snapshot: DashboardSnapshot): Boolean {
        val count = snapshot.totalCount
        fixSuggestionCount = snapshot.fixSuggestionCount
        lastScan = snapshot.lastScan
        summaryStatusIcon.icon = if (count > 0) CompileLensIcons.SummaryError else CompileLensIcons.SummaryClean
        summaryCountLabel.text = "<html><div style='text-align:center;'>$count Uncompiled Java Classes</div></html>"
        summaryModulesLabel.text = "Across ${snapshot.moduleCount} modules"
        updateFixSuggestionLabel()
        updateLastScanLabel()

        moduleListPanel.removeAll()
        snapshot.moduleCounts.forEach { (module, moduleCount) ->
            moduleListPanel.add(moduleRow(module, moduleCount))
        }
        moduleListPanel.revalidate()
        moduleListPanel.repaint()

        val modules = listOf("All Modules") + snapshot.moduleCounts.keys.sorted()
        if (moduleFilterCombo.isPopupVisible) {
            if (modules != currentModuleFilterItems()) {
                pendingModuleList = modules
            }
        } else {
            applyModuleFilterItems(modules)
        }

        return updatePreferredWidth(snapshot)
    }

    private fun currentModuleFilterItems(): List<String> =
        (0 until moduleFilterCombo.itemCount).mapNotNull { moduleFilterCombo.getItemAt(it)?.toString() }

    private fun applyModuleFilterItems(modules: List<String>) {
        if (modules == currentModuleFilterItems()) return

        val selected = moduleFilterCombo.selectedItem?.toString()
        suppressComboEvents = true
        try {
            moduleFilterCombo.model = DefaultComboBoxModel(modules.toTypedArray())
            if (selected in modules) {
                moduleFilterCombo.selectedItem = selected
            }
        } finally {
            suppressComboEvents = false
        }
    }

    private fun updatePreferredWidth(snapshot: DashboardSnapshot): Boolean {
        val sectionMetrics = getFontMetrics(CompileLensUi.sectionFont)
        val titleMetrics = getFontMetrics(CompileLensUi.titleFont)
        val smallMetrics = getFontMetrics(CompileLensUi.smallFont)
        val moduleNames = snapshot.moduleCounts.keys + listOf("All Modules", "Filters", "By Module")
        val sectionWidth = moduleNames.maxOfOrNull { sectionMetrics.stringWidth(it) } ?: 0
        val summaryWidth = titleMetrics.stringWidth("${snapshot.totalCount} Uncompiled Java Classes")
        val modulesWidth = smallMetrics.stringWidth("Across ${snapshot.moduleCount} modules")
        val suggestionsWidth = smallMetrics.stringWidth("Fix suggestions available for ${snapshot.fixSuggestionCount} classes")
        val contentWidth = maxOf(sectionWidth, summaryWidth, modulesWidth, suggestionsWidth, JBUI.scale(200))
        val width = (contentWidth + JBUI.scale(56)).coerceIn(JBUI.scale(220), JBUI.scale(380))
        if (width == lastPreferredWidth || moduleFilterCombo.isPopupVisible) return false
        lastPreferredWidth = width
        preferredSize = Dimension(width, preferredSize.height)
        revalidate()
        return true
    }

    private fun moduleRow(module: String, count: Int): JPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(CompileLensUi.rowInsets)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        add(JBLabel(module).apply { foreground = CompileLensUi.primaryText }, gbc)
        gbc.gridx = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        add(JBLabel(count.toString()).apply { foreground = CompileLensUi.mutedText }, gbc)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = LEFT_ALIGNMENT
    }

    fun selectedModule(): String? {
        val selected = moduleFilterCombo.selectedItem?.toString() ?: return null
        return if (selected == "All Modules") null else selected
    }

    fun showOnlyOpenFiles(): Boolean = showOnlyOpenFilesCheck.isSelected

    fun setRescanInProgress(inProgress: Boolean) {
        SwingUtilities.invokeLater {
            rescanButton.isEnabled = !inProgress
            rescanButton.icon = AllIcons.Actions.Refresh
            rescanButton.toolTipText = if (inProgress) {
                "Rebuilding all open projects…"
            } else {
                "Re-scan: rebuild all open projects and refresh the dashboard"
            }
        }
    }

    private fun updateFixSuggestionLabel() {
        val text = if (fixSuggestionCount > 0) {
            "Fix suggestions available for $fixSuggestionCount classes"
        } else {
            ""
        }
        SwingUtilities.invokeLater { fixSuggestionLabel.text = text }
    }

    private fun updateLastScanLabel() {
        val minutes = Duration.between(lastScan, Instant.now()).toMinutes().coerceAtLeast(0)
        val text = when {
            minutes == 0L -> "Last scan: just now"
            minutes == 1L -> "Last scan: 1 minute ago"
            else -> "Last scan: $minutes minutes ago"
        }
        SwingUtilities.invokeLater { lastScanLabel.text = text }
    }

    fun dispose() {
        relativeTimeTimer.stop()
    }
}
