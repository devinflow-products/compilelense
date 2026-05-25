package org.devinflow.compilelense.model

data class UncompiledClassRow(
    val className: String,
    val fileName: String,
    val packageName: String,
    val moduleName: String,
    val projectName: String,
    val parentFolderName: String,
    val virtualFilePath: String,
    val lineNumbers: List<Int>,
    val hasFixSuggestion: Boolean = false,
) {
    val displayModuleName: String
        get() = if (moduleName == projectName) projectName else "$projectName · $moduleName"

    companion object {
        fun fromIssues(issues: List<UncompiledIssue>): List<UncompiledClassRow> =
            issues.groupBy { it.virtualFilePath }
                .map { (_, fileIssues) ->
                    val first = fileIssues.first()
                    UncompiledClassRow(
                        className = first.className,
                        fileName = first.fileName,
                        packageName = first.packageName,
                        moduleName = first.moduleName,
                        projectName = first.projectName,
                        parentFolderName = parentFolderName(first.virtualFilePath),
                        virtualFilePath = first.virtualFilePath,
                        lineNumbers = fileIssues.map { it.lineNumber }.distinct().sorted(),
                        hasFixSuggestion = fileIssues.any { it.hasFixSuggestion },
                    )
                }

        private fun parentFolderName(virtualFilePath: String): String =
            java.io.File(virtualFilePath).parentFile?.name.orEmpty()
    }
}
