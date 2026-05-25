package org.devinflow.compilelense.scan

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.toolWindow.HighlightingProblem
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.SeverityRegistrar
import com.intellij.lang.annotation.HighlightSeverity
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

        for (problem in problems) {
            if (!isErrorProblem(problem, severityRegistrar)) continue
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

    private fun isErrorProblem(problem: Problem, severityRegistrar: SeverityRegistrar): Boolean {
        val text = sanitizeDiagnosticText(problem.text)
        if (isLoadingMessage(text)) return false

        if (problem is HighlightingProblem) {
            problem.info?.let { return severityRegistrar.compare(it.severity, HighlightSeverity.ERROR) >= 0 }
            if (problem.severity >= HighlightSeverity.ERROR.myVal) return true
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
