package org.devinflow.compilelense.model

import java.time.Instant

data class DashboardSnapshot(
    val issues: List<UncompiledIssue>,
    val lastScan: Instant,
) {
    val uncompiledClasses: List<UncompiledClassRow> by lazy { UncompiledClassRow.fromIssues(issues) }

    val totalCount: Int get() = uncompiledClasses.size

    val moduleCounts: Map<String, Int>
        get() = uncompiledClasses.groupingBy { it.moduleName }.eachCount().toList().sortedByDescending { it.second }
            .associate { it.first to it.second }

    val fixSuggestionCount: Int
        get() = uncompiledClasses.count { it.hasFixSuggestion }

    val moduleCount: Int get() = moduleCounts.size
}
