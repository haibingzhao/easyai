package com.easy.easyai.tools.calc

import groovy.lang.GroovyShell
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.control.customizers.SecureASTCustomizer
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Groovy script security sandbox.
 *
 * Two complementary layers keep LLM-generated calculation scripts confined to
 * in-memory math and date operations:
 *
 * 1. [SecureASTCustomizer] rejects dangerous import statements. Note that Groovy 5
 *    matches [SecureASTCustomizer.setDisallowedImports] by EXACT class name and
 *    [SecureASTCustomizer.setDisallowedStarImports] by package wildcard, and neither
 *    list covers classes resolved through Groovy's default imports (java.io.*, java.net.*
 *    are imported implicitly, no import statement exists to reject).
 * 2. [ResolvedTypeSecurityCustomizer] therefore runs AFTER semantic analysis and rejects
 *    any expression whose resolved type falls into a forbidden package, plus Groovy
 *    extension methods that spawn processes ('cmd'.execute()).
 */
internal object GroovySandbox {

    fun createSecureShell(): GroovyShell {
        val secure = SecureASTCustomizer().apply {
            // Exact class names (Groovy 5 disallowedImports uses exact matching only)
            disallowedImports = listOf(
                "java.lang.ProcessBuilder",
                "java.lang.ProcessHandle",
                "java.lang.Runtime",
                "java.lang.System",
                "java.lang.Thread",
                "java.lang.ClassLoader",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader"
            )
            // Package wildcards for star imports; trailing-dot entries also prefix-match
            disallowedStarImports = listOf(
                "java.io.",
                "java.nio.",
                "java.net.",
                "java.lang.reflect.",
                "java.lang.invoke.",
                "javax.script.",
                "javax.management."
            )
            disallowedStaticImports = listOf(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.Thread"
            )
            disallowedStaticStarImports = listOf(
                "java.lang.System.",
                "java.lang.Runtime.",
                "java.io.",
                "java.net.",
                "java.nio."
            )
            // Catches fully-qualified references that need no import statement
            isIndirectImportCheckEnabled = true
        }
        val config = CompilerConfiguration().apply {
            addCompilationCustomizers(secure, ResolvedTypeSecurityCustomizer())
        }
        return GroovyShell(config)
    }

    /**
     * AST visitor that runs after type resolution and blocks every expression whose
     * resolved type belongs to a forbidden package, regardless of how the class was
     * brought into scope (default import, fully-qualified name, aliased import).
     * Also blocks process-spawning extension methods by name.
     */
    private class ResolvedTypeSecurityCustomizer : CompilationCustomizer(CompilePhase.SEMANTIC_ANALYSIS) {

        override fun call(source: SourceUnit, context: GeneratorContext, classNode: ClassNode) {
            classNode.visitContents(object : ClassCodeVisitorSupport() {
                override fun getSourceUnit(): SourceUnit = source

                private fun deny(what: String, node: Expression) {
                    source.addError(
                        SyntaxException("Security violation: $what is not allowed in calc scripts", node)
                    )
                }

                private fun checkType(type: ClassNode?, node: Expression) {
                    val name = type?.name ?: return
                    if (isForbiddenType(name)) deny("use of $name", node)
                }

                override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
                    checkType(call.type, call)
                    super.visitConstructorCallExpression(call)
                }

                override fun visitClassExpression(expression: ClassExpression) {
                    checkType(expression.type, expression)
                    super.visitClassExpression(expression)
                }

                override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
                    checkType(call.ownerType, call)
                    super.visitStaticMethodCallExpression(call)
                }

                override fun visitMethodCallExpression(call: MethodCallExpression) {
                    val method = call.methodAsString
                    if (method != null && method in FORBIDDEN_METHODS) deny("method '$method'", call)
                    super.visitMethodCallExpression(call)
                }

                override fun visitMethodPointerExpression(expression: MethodPointerExpression) {
                    checkType(expression.type, expression)
                    super.visitMethodPointerExpression(expression)
                }
            })
        }

        companion object {

            private fun isForbiddenType(typeName: String): Boolean =
                typeName in FORBIDDEN_CLASSES || FORBIDDEN_PACKAGE_PREFIXES.any { typeName.startsWith(it) }

            /** Package prefixes whose classes must never appear in a calc script. */
            private val FORBIDDEN_PACKAGE_PREFIXES = setOf(
                "java.io.",
                "java.nio.",
                "java.net.",
                "java.lang.reflect.",
                "java.lang.invoke.",
                "javax.script.",
                "javax.management."
            )

            /** Individual classes that are dangerous despite living outside the prefixes above. */
            private val FORBIDDEN_CLASSES = setOf(
                "java.lang.Runtime",
                "java.lang.System",
                "java.lang.Thread",
                "java.lang.ProcessBuilder",
                "java.lang.ProcessHandle",
                "java.lang.ClassLoader",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader"
            )

            /**
             * Groovy extension methods that escape the type checks above because their
             * receiver is an innocent class (e.g. String.execute() spawns a process).
             */
            private val FORBIDDEN_METHODS = setOf("execute", "exec", "forName")
        }
    }
}
