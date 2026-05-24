package org.devinflow.compilelense.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.devinflow.compilelense.model.IssueType
import java.awt.Color
import java.awt.Font
import javax.swing.Icon

internal object CompileLensUi {
    val cardBackground: Color = JBColor(Color(0x2B2D30), Color(0x2B2D30))
    val sidebarBackground: Color = JBColor(Color(0x1E1F22), Color(0x1E1F22))
    val accentBlue: Color = JBColor(Color(0x3574F0), Color(0x3574F0))
    val mutedText: Color = JBColor(Color(0x9DA0A6), Color(0x9DA0A6))
    val primaryText: Color = JBColor(Color(0xDFE1E5), Color(0xDFE1E5))
    val borderColor: Color = JBColor(Color(0x3C3F41), Color(0x3C3F41))

    val titleFont: Font = JBUI.Fonts.label().deriveFont(Font.BOLD, 18f)
    val sectionFont: Font = JBUI.Fonts.label().deriveFont(Font.BOLD, 13f)
    val boldFont: Font = JBUI.Fonts.label().deriveFont(Font.BOLD)
    val smallFont: Font = JBUI.Fonts.label().deriveFont(11f)

    val panelInsets = JBUI.insets(12)
    val cardInsets = JBUI.insets(16)
    val rowInsets = JBUI.insets(6, 0)

    fun iconFor(issueType: IssueType): Icon = when (issueType) {
        IssueType.UNRESOLVED_IMPORT -> AllIcons.General.Error
        IssueType.CLASS_NOT_FOUND -> AllIcons.General.Warning
        IssueType.MISSING_DEPENDENCY -> AllIcons.Actions.RealIntentionBulb
        IssueType.COMPILATION_ERROR -> AllIcons.General.Error
    }

    fun iconForFilter(issueType: IssueType): Icon = iconFor(issueType)
}
