package org.devinflow.compilelense.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.model.UncompiledClassRow
import java.awt.Component
import java.awt.Cursor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumnModel
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

internal class CompileLensIssuesTable(private val project: Project) : JBTable() {

    private val tableModel = IssuesTableModel()
    private var rows: List<UncompiledClassRow> = emptyList()
    private var classColumnWidth = JBUI.scale(180)
    private var folderColumnWidth = JBUI.scale(80)
    private var locationColumnWidth = JBUI.scale(90)
    private val userColumnWidths = arrayOfNulls<Int>(3)
    private var applyingColumnLayout = false

    init {
        model = tableModel
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        rowHeight = JBUI.scale(52)
        tableHeader.reorderingAllowed = false
        tableHeader.resizingAllowed = true
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION

        columnModel.getColumn(0).cellRenderer = ClassColumnRenderer()
        columnModel.getColumn(1).cellRenderer = FolderColumnRenderer()
        columnModel.getColumn(2).cellRenderer = LocationColumnRenderer()
        columnModel.getColumn(0).resizable = true
        columnModel.getColumn(1).resizable = true
        columnModel.getColumn(2).resizable = true

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val row = rowAtPoint(e.point)
                val column = columnAtPoint(e.point)
                if (row < 0 || column < 0 || row >= rows.size) return

                val classRow = rows[row]
                when (column) {
                    1 -> navigateToParentFolder(classRow)
                    2 -> {
                        val lineNumber = lineNumberAt(e.y, row) ?: return
                        navigateToLine(classRow, lineNumber)
                    }
                    else -> if (e.clickCount == 2) {
                        navigateToLine(classRow, classRow.lineNumbers.firstOrNull() ?: return)
                    }
                }
            }
        })

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = resizeColumnsToFit()
        })

        columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent?) {
                if (applyingColumnLayout) return
                val resizingColumn = tableHeader.resizingColumn ?: return
                val columnIndex = resizingColumn.modelIndex
                if (columnIndex !in 0..2) return
                userColumnWidths[columnIndex] = resizingColumn.width.coerceAtLeast(minWidthFor(columnIndex))
                resizeColumnsToFit()
            }

            override fun columnAdded(e: TableColumnModelEvent?) = Unit
            override fun columnRemoved(e: TableColumnModelEvent?) = Unit
            override fun columnMoved(e: TableColumnModelEvent?) = Unit
            override fun columnSelectionChanged(e: javax.swing.event.ListSelectionEvent?) = Unit
        })
    }

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun getScrollableTracksViewportWidth(): Boolean = false

    fun onViewportResized() = resizeColumnsToFit()

    fun setIssues(issues: List<UncompiledClassRow>) {
        rows = issues
        tableModel.fireTableDataChanged()
        issues.indices.forEach { index ->
            setRowHeight(index, computeRowHeight(issues[index]))
        }
        updateClassColumnWidth(issues)
        updateFolderColumnWidth(issues)
        updateLocationColumnWidth(issues)
        resizeColumnsToFit()
    }

    private fun updateClassColumnWidth(issues: List<UncompiledClassRow>) {
        val boldMetrics = getFontMetrics(CompileLensUi.boldFont)
        val smallMetrics = getFontMetrics(CompileLensUi.smallFont)
        val iconWidth = AllIcons.General.Error.iconWidth + JBUI.scale(16)
        val horizontalPadding = JBUI.scale(24)
        val headerWidth = boldMetrics.stringWidth("Class / File") + horizontalPadding
        val maxFileWidth = issues.maxOfOrNull { boldMetrics.stringWidth(it.fileName) } ?: 0
        val maxPackageWidth = issues.maxOfOrNull { smallMetrics.stringWidth(it.packageName) } ?: 0
        classColumnWidth = maxOf(
            JBUI.scale(140),
            headerWidth,
            maxFileWidth + horizontalPadding,
            maxPackageWidth + iconWidth + horizontalPadding,
        )
    }

    private fun updateFolderColumnWidth(issues: List<UncompiledClassRow>) {
        val metrics = getFontMetrics(CompileLensUi.smallFont)
        val iconWidth = AllIcons.Nodes.Folder.iconWidth + JBUI.scale(8)
        val horizontalPadding = JBUI.scale(20)
        val headerWidth = metrics.stringWidth("Folder") + horizontalPadding
        val maxTextWidth = issues.maxOfOrNull { metrics.stringWidth(it.parentFolderName) } ?: 0
        folderColumnWidth = maxOf(JBUI.scale(60), headerWidth, maxTextWidth + iconWidth + horizontalPadding)
    }

    private fun updateLocationColumnWidth(issues: List<UncompiledClassRow>) {
        val metrics = getFontMetrics(CompileLensUi.smallFont)
        val horizontalPadding = JBUI.scale(16)
        val headerWidth = metrics.stringWidth("Location") + horizontalPadding
        val maxLineWidth = issues.maxOfOrNull { row ->
            row.lineNumbers.maxOfOrNull { metrics.stringWidth("line $it") } ?: 0
        } ?: 0
        locationColumnWidth = maxOf(JBUI.scale(72), headerWidth, maxLineWidth + horizontalPadding)
    }

    private fun availableWidth(): Int {
        var component: Component? = parent
        while (component != null) {
            if (component is javax.swing.JScrollPane) {
                return component.viewport.width
            }
            component = component.parent
        }
        return width
    }

    private fun resizeColumnsToFit() {
        if (width <= 0 || columnModel.columnCount < 3) return

        val columnModel = columnModel
        val available = availableWidth()
        if (available <= 0) return

        val minClass = JBUI.scale(120)
        val minFolder = JBUI.scale(60)
        val minLocation = JBUI.scale(72)

        val naturalWidths = intArrayOf(classColumnWidth, folderColumnWidth, locationColumnWidth)
        val naturalTotal = naturalWidths.sum()
        val widths = IntArray(3)
        val minTotal = minClass + minFolder + minLocation

        if (userColumnWidths.any { it != null }) {
            for (index in 0..2) {
                widths[index] = (userColumnWidths[index] ?: naturalWidths[index]).coerceAtLeast(minWidthFor(index))
            }
            applyColumnWidths(columnModel, maxOf(available, widths.sum()), widths)
            return
        }

        if (available <= minTotal) {
            widths[0] = minClass
            widths[1] = minFolder
            widths[2] = (available - minClass - minFolder).coerceAtLeast(minLocation)
            applyColumnWidths(columnModel, available, widths)
            return
        }

        if (naturalTotal <= available) {
            val extra = available - naturalTotal
            val perColumnExtra = extra / 3
            widths[0] = naturalWidths[0] + perColumnExtra
            widths[1] = naturalWidths[1] + perColumnExtra
            widths[2] = naturalWidths[2] + (extra - (perColumnExtra * 2))
        } else {
            val flexible = naturalWidths.mapIndexed { index, width ->
                when (index) {
                    0 -> (width - minClass).coerceAtLeast(0)
                    1 -> (width - minFolder).coerceAtLeast(0)
                    else -> (width - minLocation).coerceAtLeast(0)
                }
            }
            val flexibleTotal = flexible.sum().coerceAtLeast(1)
            val distributable = available - minTotal
            widths[0] = minClass + (distributable * flexible[0] / flexibleTotal)
            widths[1] = minFolder + (distributable * flexible[1] / flexibleTotal)
            widths[2] = available - widths[0] - widths[1]
        }

        applyColumnWidths(columnModel, available, widths)
    }

    private fun preferredTableHeight(): Int {
        val headerHeight = tableHeader.preferredSize.height
        if (rowCount == 0) return headerHeight + rowHeight
        var height = headerHeight
        for (row in 0 until rowCount) {
            height += getRowHeight(row)
        }
        return height
    }

    private fun applyColumnWidths(columnModel: TableColumnModel, available: Int, widths: IntArray) {
        applyingColumnLayout = true
        try {
            setColumnWidth(columnModel, 0, widths[0])
            setColumnWidth(columnModel, 1, widths[1])
            setColumnWidth(columnModel, 2, widths[2])
            preferredSize = java.awt.Dimension(
                maxOf(available, widths.sum()),
                preferredTableHeight(),
            )
            revalidate()
        } finally {
            applyingColumnLayout = false
        }
    }

    private fun setColumnWidth(columnModel: TableColumnModel, index: Int, width: Int) {
        columnModel.getColumn(index).apply {
            preferredWidth = width
            minWidth = minWidthFor(index)
            maxWidth = Int.MAX_VALUE
        }
    }

    private fun minWidthFor(index: Int): Int = when (index) {
        0 -> JBUI.scale(120)
        1 -> JBUI.scale(60)
        else -> JBUI.scale(72)
    }

    private fun computeRowHeight(row: UncompiledClassRow): Int {
        val lineHeight = JBUI.scale(18)
        val padding = JBUI.scale(16)
        return maxOf(JBUI.scale(52), row.lineNumbers.size * lineHeight + padding)
    }

    private fun lineNumberAt(mouseY: Int, row: Int): Int? {
        val classRow = rows.getOrNull(row) ?: return null
        if (classRow.lineNumbers.isEmpty()) return null

        val cellRect = getCellRect(row, 2, false)
        val relativeY = mouseY - cellRect.y - JBUI.scale(4)
        if (relativeY < 0) return classRow.lineNumbers.first()

        val lineHeight = JBUI.scale(18)
        val lineIndex = (relativeY / lineHeight).coerceIn(0, classRow.lineNumbers.lastIndex)
        return classRow.lineNumbers[lineIndex]
    }

    private fun navigateToLine(classRow: UncompiledClassRow, lineNumber: Int) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(classRow.virtualFilePath) ?: return
        OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0).navigate(true)
    }

    private fun navigateToParentFolder(classRow: UncompiledClassRow) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(classRow.virtualFilePath) ?: return
        val parentFolder = virtualFile.parent ?: return
        OpenFileDescriptor(project, parentFolder).navigate(true)
    }

    private inner class IssuesTableModel : AbstractTableModel() {
        private val columns = arrayOf("Class / File", "Folder", "Location")

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            0 -> rows[rowIndex]
            1 -> rows[rowIndex]
            2 -> rows[rowIndex]
            else -> ""
        }
    }

    private class ClassColumnRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val panel = javax.swing.JPanel(java.awt.BorderLayout(8, 0)).apply {
                isOpaque = true
                background = if (isSelected) table?.selectionBackground else table?.background
                border = JBUI.Borders.empty(0, 8, 0, 4)
            }
            if (value is UncompiledClassRow) {
                panel.add(
                    javax.swing.JLabel(AllIcons.General.Error),
                    java.awt.BorderLayout.WEST,
                )
                val textPanel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                    isOpaque = false
                    add(javax.swing.JLabel(value.fileName).apply {
                        font = CompileLensUi.boldFont
                        foreground = CompileLensUi.primaryText
                    }, java.awt.BorderLayout.NORTH)
                    add(javax.swing.JLabel(value.packageName).apply {
                        font = CompileLensUi.smallFont
                        foreground = CompileLensUi.mutedText
                    }, java.awt.BorderLayout.SOUTH)
                }
                panel.add(textPanel, java.awt.BorderLayout.CENTER)
            }
            return panel
        }
    }

    private class FolderColumnRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val panel = javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = true
                background = if (isSelected) table?.selectionBackground else table?.background
                border = JBUI.Borders.empty(0, 4)
            }
            if (value is UncompiledClassRow) {
                panel.add(javax.swing.JLabel(AllIcons.Nodes.Folder))
                panel.add(javax.swing.JLabel(value.parentFolderName).apply {
                    font = CompileLensUi.smallFont
                    foreground = CompileLensUi.accentBlue
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                })
            }
            return panel
        }
    }

    private class LocationColumnRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val panel = javax.swing.JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                isOpaque = true
                background = if (isSelected) table?.selectionBackground else table?.background
                border = JBUI.Borders.empty(4, 4, 4, 8)
            }
            if (value is UncompiledClassRow) {
                value.lineNumbers.forEach { lineNumber ->
                    panel.add(
                        javax.swing.JLabel("line $lineNumber").apply {
                            font = CompileLensUi.smallFont
                            foreground = CompileLensUi.accentBlue
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                }
            }
            return panel
        }
    }
}
