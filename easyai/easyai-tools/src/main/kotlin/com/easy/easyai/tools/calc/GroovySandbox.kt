package com.easy.easyai.tools.calc

import groovy.lang.GroovyShell
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.VariableScope
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.WhileStatement
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
 *
 * Resource protection: [LoopInstrumentationCustomizer] injects a [ScriptLoopGuard] tick
 * at the head of every loop and closure body, so pure-CPU runaway scripts are aborted
 * deterministically at a step limit — `Thread.interrupt()` cannot stop tight loops.
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
        // Order matters: ForbiddenCallsCustomizer must run first, so it never sees the
        // ScriptLoopGuard.tick() calls injected afterwards by LoopInstrumentationCustomizer.
        config.addCompilationCustomizers(ForbiddenCallsCustomizer(), LoopInstrumentationCustomizer())
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
            // Scripts must not tamper with the step guard (e.g. disarm() it or arm() a
            // huge limit). Covers bare names and fully qualified references alike.
            val isGuardAccess = receiver.text.let { it == "ScriptLoopGuard" || it.endsWith(".ScriptLoopGuard") }
            if (isClassForName || isStringExecute || isGuardAccess) {
                throw SyntaxException(
                    "Security violation: '$method' is blocked by the calc sandbox", -1, -1
                )
            }
        }
    }

    /**
     * Injects a [ScriptLoopGuard] tick at the head of every loop body
     * (while / do-while / for) and every closure body, so iteration-style abuse
     * (`list.each { while (true) {} }`) is bounded as well.
     *
     * Runs after [ForbiddenCallsCustomizer] (registration order) so injected ticks
     * are not mistaken for script-written guard access.
     */
    private class LoopInstrumentationCustomizer : CompilationCustomizer(CompilePhase.CONVERSION) {

        override fun call(sourceUnit: SourceUnit, context: GeneratorContext, classNode: ClassNode) {
            val visitor = object : ClassCodeVisitorSupport() {
                override fun visitWhileLoop(loop: WhileStatement) {
                    loop.loopBlock = instrumented(loop.loopBlock)
                    super.visitWhileLoop(loop)
                }

                override fun visitForLoop(loop: ForStatement) {
                    loop.loopBlock = instrumented(loop.loopBlock)
                    super.visitForLoop(loop)
                }

                override fun visitDoWhileLoop(loop: DoWhileStatement) {
                    loop.loopBlock = instrumented(loop.loopBlock)
                    super.visitDoWhileLoop(loop)
                }

                override fun visitClosureExpression(expression: ClosureExpression) {
                    expression.code?.let { expression.code = instrumented(it) }
                    super.visitClosureExpression(expression)
                }

                override fun getSourceUnit(): SourceUnit = sourceUnit
            }
            visitor.visitClass(classNode)
        }

        private fun instrumented(body: Statement): Statement {
            val tick = ExpressionStatement(
                StaticMethodCallExpression(
                    ClassHelper.make(ScriptLoopGuard::class.java),
                    "tick",
                    ArgumentListExpression.EMPTY_ARGUMENTS
                )
            )
            if (body is BlockStatement) {
                body.statements.add(0, tick)
                return body
            }
            return BlockStatement(mutableListOf(tick, body), VariableScope())
        }
    }
}

/**
 * Per-thread step counter that deterministically aborts runaway scripts.
 *
 * The caller [arm]s the guard on the thread that will evaluate the script and
 * [disarm]s in a `finally` block; [tick] is injected by [LoopInstrumentationCustomizer]
 * at the head of every loop/closure body. When the budget is exhausted, [tick] throws
 * [ScriptStepLimitException], unwinding the script like any runtime exception —
 * unlike `Thread.interrupt()`, which a pure-CPU loop never observes.
 */
internal object ScriptLoopGuard {

    /** Unarmed sentinel: a tick without a prior [arm] is a no-op (fail-open for bare shells). */
    private const val UNARMED = -1L

    private val remaining = ThreadLocal.withInitial { longArrayOf(UNARMED) }

    /** Sets the step budget for the current thread. Must be paired with [disarm]. */
    fun arm(maxSteps: Long) {
        remaining.get()[0] = maxSteps
    }

    /** Clears the step budget for the current thread. */
    fun disarm() {
        remaining.remove()
    }

    /** Consumed one step; throws [ScriptStepLimitException] when the budget is spent. */
    @JvmStatic
    fun tick() {
        val counter = remaining.get()
        val left = counter[0]
        if (left == UNARMED) return
        if (left <= 0L) throw ScriptStepLimitException()
        counter[0] = left - 1
    }
}

/** Thrown by [ScriptLoopGuard.tick] when a script exceeds its step budget. */
internal class ScriptStepLimitException : RuntimeException(
    "Script exceeded the step limit: likely an infinite loop"
)
