package com.easy.easyai.tools.calc

import groovy.lang.GroovyShell
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.syntax.SyntaxException

/**
 * Groovy script security sandbox.
 *
 * Uses a custom [ClassLoader] to prevent loading classes from dangerous packages
 * (IO / network / reflection / scripting engines), ensuring LLM-generated calculation
 * scripts can only perform math and date operations in memory.
 *
 * This approach is more robust than [org.codehaus.groovy.control.customizers.SecureASTCustomizer]
 * because it blocks classes regardless of how they are referenced — explicit import,
 * Groovy default import, or fully qualified name.
 */
internal object GroovySandbox {

    /**
     * Packages blocked at class-loading level.
     * Covers all classes that could enable file I/O, network access, process spawning,
     * reflection, or scripting engine abuse.
     */
    private val BLOCKED_PACKAGES = setOf(
        "java.io",
        "java.nio.file",
        "java.net",
        "java.lang.reflect",
        "javax.script",
        "javax.management"
    )

    /**
     * Individual classes blocked at class-loading level.
     * These are not in blocked packages but are individually dangerous
     * (e.g., process spawning, thread manipulation).
     */
    private val BLOCKED_CLASSES = setOf(
        "java.lang.System",
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.Thread",
        "groovy.lang.GroovyShell",
        "groovy.lang.GroovyClassLoader"
    )

    fun createSecureShell(): GroovyShell {
        val config = CompilerConfiguration()
        config.addCompilationCustomizers(ForbiddenCallsCustomizer())
        val sandboxLoader = SandboxedClassLoader(GroovySandbox::class.java.classLoader)
        return GroovyShell(sandboxLoader, config)
    }

    /**
     * Custom ClassLoader that refuses to load classes from [BLOCKED_PACKAGES]
     * or listed in [BLOCKED_CLASSES].
     *
     * When a Groovy script references `new File(path)`, the compiler tries to resolve
     * `java.io.File` via the default import `java.io.*`. This loader intercepts
     * the class lookup and throws [ClassNotFoundException] for any blocked class.
     */
    private class SandboxedClassLoader(parent: ClassLoader) : ClassLoader(parent) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (isBlocked(name)) {
                throw ClassNotFoundException("Access denied: '$name' is blocked by the calc sandbox")
            }
            return super.loadClass(name, resolve)
        }

        override fun findClass(name: String): Class<*> {
            if (isBlocked(name)) {
                throw ClassNotFoundException("Access denied: '$name' is blocked by the calc sandbox")
            }
            return super.findClass(name)
        }

        private fun isBlocked(name: String): Boolean {
            return name in BLOCKED_CLASSES || BLOCKED_PACKAGES.any { name.startsWith("$it.") }
        }
    }

    /**
     * Compile-time rejections for calls that bypass the classloader sandbox at runtime:
     * - `Class.forName(...)`: loads classes through the caller's class loader, which is
     *   the Groovy runtime loader, not [SandboxedClassLoader].
     * - `'...'.execute()`: the `ProcessGroovyMethods` DGM extension is resolved at
     *   runtime via the meta-class and never passes through the sandbox loader.
     */
    private class ForbiddenCallsCustomizer : CompilationCustomizer(CompilePhase.CONVERSION) {

        override fun call(sourceUnit: SourceUnit, context: GeneratorContext, classNode: ClassNode) {
            val visitor = object : ClassCodeVisitorSupport() {
                override fun visitMethodCallExpression(call: MethodCallExpression) {
                    rejectIfForbidden(call.objectExpression, call.methodAsString)
                    super.visitMethodCallExpression(call)
                }

                override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
                    rejectIfForbidden(ClassExpression(call.ownerType), call.method)
                    super.visitStaticMethodCallExpression(call)
                }

                override fun getSourceUnit(): SourceUnit = sourceUnit
            }
            visitor.visitClass(classNode)
        }

        private fun rejectIfForbidden(receiver: Expression, method: String) {
            val isClassForName = method == "forName" &&
                (receiver is ClassExpression && receiver.type.name == "java.lang.Class")
            val isStringExecute = method == "execute" &&
                receiver is ConstantExpression && receiver.value is String
            if (isClassForName || isStringExecute) {
                throw SyntaxException(
                    "Security violation: '$method' is blocked by the calc sandbox", -1, -1
                )
            }
        }
    }
}
