package org.devinflow.compilelense.model

data class UncompiledIssue(
    val className: String,
    val fileName: String,
    val packageName: String,
    val issueType: IssueType,
    val issueSummary: String,
    val issueDetail: String,
    val moduleName: String,
    val lineNumber: Int,
    val virtualFilePath: String,
    val hasFixSuggestion: Boolean = false,
)
