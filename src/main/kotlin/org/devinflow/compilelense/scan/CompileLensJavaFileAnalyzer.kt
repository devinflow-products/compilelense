package org.devinflow.compilelense.scan

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import org.devinflow.compilelense.model.IssueType
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

/**
 * Collects compilation errors for a single Java source file using IntelliJ's analysis engine.
 */
internal object CompileLensJavaFileAnalyzer {

    fun analyzeFile(
        ownerProject: Project,
        virtualFile: VirtualFile,
        psiFile: PsiJavaFile,
        isOpenInEditor: Boolean,
    ): List<UncompiledIssue> {
        val seen = LinkedHashSet<String>()
        val results = ArrayList<UncompiledIssue>()
        val normalizedPath = CompileLensPaths.normalize(virtualFile)
        val preferDocument = isOpenInEditor ||
            CompileLensAnalysisSession.isDirtyInAnyProject(normalizedPath)
        if (preferDocument) {
            collectFromDocumentHighlights(ownerProject, virtualFile, psiFile, seen, results)
        }
        collectFromProblemsCollector(ownerProject, virtualFile, psiFile, seen, results)
        return results
    }

    private fun collectFromProblemsCollector(
        ownerProject: Project,
        virtualFile: VirtualFile,
        psiFile: PsiJavaFile,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
    ) {
        val problems = ProblemsCollector.getInstance(ownerProject).getFileProblems(virtualFile)
        if (problems.isEmpty()) return

        val severityRegistrar = SeverityRegistrar.getSeverityRegistrar(ownerProject)
        val context = fileContext(ownerProject, virtualFile, psiFile)
        val errorLines = errorHighlightLines(ownerProject, virtualFile, psiFile, severityRegistrar)

        for (problem in problems) {
            if (!isErrorProblem(problem, errorLines)) continue
            val line = problemLine(problem)
            val detail = problemMessage(problem)
            if (detail.isBlank() || isLoadingMessage(detail)) continue
            addIssue(
                seen,
                results,
                key = "${virtualFile.path}:problem:${detail.lowercase(Locale.getDefault())}:$line",
                className = context.className,
                fileName = context.fileName,
                packageName = context.packageName,
                issueType = classifyDiagnosticIssue(detail),
                issueSummary = classifyDiagnosticIssue(detail).displayName,
                issueDetail = detail,
                moduleName = context.moduleName,
                projectName = context.projectName,
                lineNumber = line,
                filePath = CompileLensPaths.normalize(virtualFile),
            )
        }
    }

    private fun collectFromDocumentHighlights(
        ownerProject: Project,
        virtualFile: VirtualFile,
        psiFile: PsiJavaFile,
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
    ) {
        if (!virtualFile.isValid || virtualFile.isDirectory) return
        val document = documentFor(ownerProject, psiFile, virtualFile) ?: return

        val severityRegistrar = SeverityRegistrar.getSeverityRegistrar(ownerProject)
        val highlights = CompileLensHighlightCollector.collectErrorHighlights(ownerProject, document, severityRegistrar)
        if (highlights.isEmpty()) return

        val context = fileContext(ownerProject, virtualFile, psiFile)
        for (highlight in highlights) {
            val line = lineForOffset(document.textLength, highlight.actualStartOffset, document)
            val detail = sanitizeDiagnosticText(highlight.description ?: highlight.toolTip ?: "Compilation error")
            val issueType = classifyDiagnosticIssue(detail)
            addIssue(
                seen,
                results,
                key = "${virtualFile.path}:highlight:${detail.lowercase(Locale.getDefault())}:$line",
                className = context.className,
                fileName = context.fileName,
                packageName = context.packageName,
                issueType = issueType,
                issueSummary = issueType.displayName,
                issueDetail = detail,
                moduleName = context.moduleName,
                projectName = context.projectName,
                lineNumber = line,
                filePath = CompileLensPaths.normalize(virtualFile),
            )
        }
    }

    private fun documentFor(project: Project, psiFile: PsiJavaFile, virtualFile: VirtualFile): Document? =
        FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: PsiDocumentManager.getInstance(project).getDocument(psiFile)

    private data class FileContext(
        val className: String,
        val fileName: String,
        val packageName: String,
        val moduleName: String,
        val projectName: String,
    )

    private fun fileContext(project: Project, virtualFile: VirtualFile, psiFile: PsiJavaFile): FileContext {
        val fileName = virtualFile.name
        return FileContext(
            className = psiFile.classes.firstOrNull()?.name ?: fileName.removeSuffix(".java"),
            fileName = fileName,
            packageName = psiFile.packageName.ifBlank { "(default package)" },
            moduleName = ModuleUtil.findModuleForFile(virtualFile, project)?.name ?: project.name,
            projectName = project.name,
        )
    }

    /**
     * Determines whether a [Problem] reported by `ProblemsCollector` should be treated as a
     * compilation error.
     *
     * `ProblemsCollector.getFileProblems` returns problems of every severity (errors, warnings,
     * weak warnings, infos), so we have to filter to error-severity ourselves. The previous
     * implementation reached into `HighlightingProblem.getInfo()` / `getSeverity()`, but those
     * accessors and the `HighlightingProblem` class itself are `@ApiStatus.Internal` and
     * therefore off-limits to third-party plugins.
     *
     * Instead we treat the document's markup model as the authoritative source of "is there an
     * error-severity highlight on this line right now?". [errorLines] is the set of lines that
     * currently carry an error-severity range highlighter for this file:
     *
     *  - `null`     -> the document/markup model is unavailable (closed file with no document
     *                  loaded, or invalid VFS file); fall back to text-keyword heuristics.
     *  - non-null   -> the markup is authoritative; a problem whose line is not in [errorLines]
     *                  is NOT an error, regardless of what its message text says.
     *
     * The previous version always fell back to text heuristics whenever the line was missing
     * from [errorLines], including for open files. That re-promoted warnings/infos whose text
     * happens to contain words like "cannot resolve" or "unresolved" to ERROR — producing
     * phantom dashboard rows on lines that have no error highlight (e.g. "line 6" entries for
     * files whose real errors are on different lines). Trusting the markup when it exists
     * eliminates that class of false positive.
     */
    private fun isErrorProblem(problem: Problem, errorLines: Set<Int>?): Boolean {
        val text = sanitizeDiagnosticText(problem.text)
        if (isLoadingMessage(text)) return false

        if (errorLines != null) {
            return problemLine(problem) in errorLines
        }

        val lower = text.lowercase(Locale.getDefault())
        if (lower.contains("never used") || lower.contains("unused import")) return false
        return lower.contains("cannot resolve")
            || lower.contains("cannot find symbol")
            || lower.contains("cannot resolve method")
            || lower.contains("cannot be applied")
            || lower.contains("identifier expected")
            || lower.contains("';' expected")
            || lower.contains("cannot access")
            || lower.contains("incompatible types")
            || lower.contains("syntax error")
            || lower.contains("unresolved")
    }

    /**
     * Returns the 1-based line numbers that currently carry an error-severity range
     * highlighter in the document's markup model, or `null` if the markup model is not
     * accessible (no document, invalid VFS file). A non-null empty set means "document
     * exists and the daemon reports zero error highlights" — that is authoritative.
     */
    private fun errorHighlightLines(
        project: Project,
        virtualFile: VirtualFile,
        psiFile: PsiJavaFile,
        severityRegistrar: SeverityRegistrar,
    ): Set<Int>? {
        if (!virtualFile.isValid || virtualFile.isDirectory) return null
        val document = documentFor(project, psiFile, virtualFile) ?: return null
        val highlights = CompileLensHighlightCollector.collectErrorHighlights(
            project,
            document,
            severityRegistrar,
        )
        if (highlights.isEmpty()) return emptySet()
        val lines = HashSet<Int>()
        val textLength = document.textLength
        for (info in highlights) {
            lines += lineForOffset(textLength, info.actualStartOffset, document)
        }
        return lines
    }

    private fun problemLine(problem: Problem): Int {
        val fileProblem = problem as? FileProblem ?: return 1
        val line = fileProblem.line
        return if (line >= 0) line + 1 else 1
    }

    private fun problemMessage(problem: Problem): String {
        val text = sanitizeDiagnosticText(problem.text)
        val description = problem.description?.let { sanitizeDiagnosticText(it) }
        return when {
            description != null && description != text && description.isNotBlank() -> description
            else -> text
        }
    }

    private fun lineForOffset(textLength: Int, offset: Int, document: Document): Int {
        if (textLength <= 0) return 1
        val safeOffset = offset.coerceIn(0, textLength - 1)
        return document.getLineNumber(safeOffset) + 1
    }

    private fun sanitizeDiagnosticText(raw: String): String {
        val noHtml = raw.replace(Regex("<[^>]*>"), " ")
        return noHtml.replace(Regex("\\s+"), " ").trim().ifBlank { "Compilation error" }
    }

    private fun isLoadingMessage(text: String): Boolean =
        text.equals("Loading...", ignoreCase = true)

    private fun classifyDiagnosticIssue(detail: String): IssueType {
        val lower = detail.lowercase(Locale.getDefault())
        if ("import" in lower && ("cannot resolve" in lower || "cannot find symbol" in lower)) {
            return IssueType.UNRESOLVED_IMPORT
        }
        if ("package" in lower && "does not exist" in lower) {
            return IssueType.MISSING_DEPENDENCY
        }
        if ("cannot resolve symbol" in lower || "cannot find symbol" in lower || "cannot access" in lower) {
            return if ("class " in lower || "interface " in lower || "enum " in lower) {
                IssueType.CLASS_NOT_FOUND
            } else {
                IssueType.MISSING_DEPENDENCY
            }
        }
        if ("class not found" in lower || "cannot find class" in lower) {
            return IssueType.CLASS_NOT_FOUND
        }
        return IssueType.COMPILATION_ERROR
    }

    private fun addIssue(
        seen: MutableSet<String>,
        results: MutableList<UncompiledIssue>,
        key: String,
        className: String,
        fileName: String,
        packageName: String,
        issueType: IssueType,
        issueSummary: String,
        issueDetail: String,
        moduleName: String,
        projectName: String,
        lineNumber: Int,
        filePath: String,
    ) {
        if (!seen.add(key)) return
        val hasFixSuggestion = issueType == IssueType.MISSING_DEPENDENCY || issueType == IssueType.UNRESOLVED_IMPORT
        results += UncompiledIssue(
            className = className,
            fileName = fileName,
            packageName = packageName,
            issueType = issueType,
            issueSummary = issueSummary.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
            issueDetail = issueDetail,
            moduleName = moduleName,
            projectName = projectName,
            lineNumber = lineNumber,
            virtualFilePath = filePath,
            hasFixSuggestion = hasFixSuggestion,
        )
    }
}
