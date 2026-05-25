package org.devinflow.compilelense.scan

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project

internal object CompileLensHighlightCollector {

    fun hasErrorHighlights(
        project: Project,
        document: Document,
        severityRegistrar: SeverityRegistrar = SeverityRegistrar.getSeverityRegistrar(project),
    ): Boolean {
        val model = DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx ?: return false
        var found = false
        model.processRangeHighlightersOverlappingWith(0, document.textLength) { marker ->
            val info = HighlightInfo.fromRangeHighlighter(marker) ?: return@processRangeHighlightersOverlappingWith true
            if (info.highlighter == marker && severityRegistrar.compare(info.severity, HighlightSeverity.ERROR) >= 0) {
                found = true
                return@processRangeHighlightersOverlappingWith false
            }
            true
        }
        return found
    }

    fun collectErrorHighlights(
        project: Project,
        document: Document,
        severityRegistrar: SeverityRegistrar = SeverityRegistrar.getSeverityRegistrar(project),
    ): List<HighlightInfo> {
        val highlights = LinkedHashSet<HighlightInfo>()
        val model = DocumentMarkupModel.forDocument(document, project, false) as? MarkupModelEx ?: return emptyList()
        model.processRangeHighlightersOverlappingWith(0, document.textLength) { marker ->
            val info = HighlightInfo.fromRangeHighlighter(marker) ?: return@processRangeHighlightersOverlappingWith true
            if (info.highlighter == marker && severityRegistrar.compare(info.severity, HighlightSeverity.ERROR) >= 0) {
                highlights.add(info)
            }
            true
        }
        return highlights.toList()
    }
}
