package com.easy.easyai.tools.calc

import groovy.lang.GroovyShell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * Regression tests for [GroovySandbox] escape prevention.
 *
 * Historical bug: the sandbox relied solely on SecureASTCustomizer.disallowedImports,
 * which in Groovy 5 matches by exact class name and only inspects EXPLICIT import
 * statements. Groovy default-imports java.io.* / java.net.*, so `new File(...).text`,
 * `new URL(...)`, `'cmd'.execute()` and file writes all bypassed the sandbox while
 * the KDoc claimed "no file I/O, no network, no process spawning". These tests pin
 * down the hardened two-layer sandbox (import checks + resolved-type visitor).
 */
class GroovySandboxTest {

    @TempDir
    lateinit var tempDir: Path

    private fun freshShell(): GroovyShell = GroovySandbox.createSecureShell()

    private fun assertBlocked(script: String) {
        // The sandbox rejects via ClassNotFoundException, which the JVM may re-wrap as
        // NoClassDefFoundError while linking a blocked java.* class — accept any Throwable.
        val exception = assertThrows(Throwable::class.java) { freshShell().evaluate(script) }
        val message = exception.message ?: ""
        val rejected = exception is NoClassDefFoundError || exception is ClassNotFoundException ||
            message.contains("Security violation") || message.contains("Access denied") ||
            message.contains("not allowed")
        assertTrue(
            rejected,
            "expected a security rejection for [$script] but got: ${message.lines().firstOrNull()}"
        )
    }

    @Nested
    inner class `File system escapes` {

        @Test
        fun `blocks file read via default import`() {
            val target = tempDir.resolve("secret.txt")
            java.nio.file.Files.writeString(target, "secret")
            assertBlocked("new File('$target').text")
        }

        @Test
        fun `blocks file write`() {
            val target = tempDir.resolve("victim.txt")
            assertBlocked("new File('$target').text = 'pwned'")
            assertTrue(java.nio.file.Files.notExists(target), "the write must not reach the file system")
        }

        @Test
        fun `blocks directory listing`() {
            assertBlocked("new File('$tempDir').list().length")
        }

        @Test
        fun `blocks explicit java io import`() {
            assertBlocked("import java.io.File\nnew File('$tempDir').list()")
        }

        @Test
        fun `blocks fully qualified nio access`() {
            assertBlocked("java.nio.file.Files.readString(java.nio.file.Path.of('$tempDir/x'))")
        }
    }

    @Nested
    inner class `Network escapes` {

        @Test
        fun `blocks URL construction`() {
            assertBlocked("new URL('http://example.invalid').toString()")
        }

        @Test
        fun `blocks URL class reference`() {
            assertBlocked("def c = URL; c.toString()")
        }
    }

    @Nested
    inner class `Process escapes` {

        @Test
        fun `blocks String execute extension method`() {
            assertBlocked("'ls'.execute().text")
        }

        @Test
        fun `blocks Runtime exec`() {
            assertBlocked("Runtime.getRuntime().exec('ls').waitFor()")
        }

        @Test
        fun `blocks ProcessBuilder construction`() {
            assertBlocked("new ProcessBuilder('ls').start()")
        }

        @Test
        fun `blocks thread creation`() {
            assertBlocked("new Thread({}).start()")
        }
    }

    @Nested
    inner class `Reflection escapes` {

        @Test
        fun `blocks Class forName`() {
            assertBlocked("Class.forName('java.io.File').toString()")
        }
    }

    @Nested
    inner class `Step guard` {

        @Test
        fun `armed guard aborts an infinite loop at the step budget`() {
            ScriptLoopGuard.arm(100)
            try {
                val exception = assertThrows(ScriptStepLimitException::class.java) {
                    freshShell().evaluate("while (true) { }")
                }
                assertTrue(exception.message!!.contains("step limit"))
            } finally {
                ScriptLoopGuard.disarm()
            }
        }

        @Test
        fun `armed guard counts closure-driven iterations`() {
            ScriptLoopGuard.arm(10)
            try {
                assertThrows(ScriptStepLimitException::class.java) {
                    freshShell().evaluate("(1..100).each { it * 2 }")
                }
            } finally {
                ScriptLoopGuard.disarm()
            }
        }

        @Test
        fun `unarmed guard is a no-op so bare shells keep working`() {
            assertEquals("6", freshShell().evaluate("(1..3).sum()").toString())
        }

        @Test
        fun `budget resets between executions on the same thread`() {
            ScriptLoopGuard.arm(5)
            try {
                assertThrows(ScriptStepLimitException::class.java) {
                    freshShell().evaluate("while (true) { }")
                }
            } finally {
                ScriptLoopGuard.disarm()
            }
            // A fresh arm must start from the full budget again
            ScriptLoopGuard.arm(1_000)
            try {
                assertEquals("55", freshShell().evaluate("(1..10).sum()").toString())
            } finally {
                ScriptLoopGuard.disarm()
            }
        }

        @Test
        fun `blocks script access to the guard by bare name`() {
            assertBlocked("ScriptLoopGuard.disarm()")
        }

        @Test
        fun `blocks script access to the guard by qualified name`() {
            assertBlocked("com.easy.easyai.tools.calc.ScriptLoopGuard.arm(999999999999)")
        }
    }

    @Nested
    inner class `Legitimate calculations still work` {

        @Test
        fun `evaluates arithmetic`() {
            assertEquals("14", freshShell().evaluate("2 + 3 * 4").toString())
        }

        @Test
        fun `evaluates list closures`() {
            assertEquals("12", freshShell().evaluate("def l = [1,2,3]; l.collect { it * 2 }.sum()").toString())
        }

        @Test
        fun `evaluates string operations`() {
            assertEquals("ABCxxx", freshShell().evaluate("'abc'.toUpperCase() + 'x'.repeat(3)").toString())
        }

        @Test
        fun `captures output written to the bound out variable`() {
            val shell = freshShell()
            val capture = StringWriter()
            shell.context.setVariable("out", PrintWriter(capture))
            val result = shell.evaluate("out.println('hello'); 42")
            assertEquals("42", result.toString())
            assertEquals("hello", capture.toString().trim())
        }

        @Test
        fun `evaluates math functions`() {
            assertEquals("4.0", freshShell().evaluate("Math.sqrt(16)").toString())
        }

        @Test
        fun `evaluates java time calculations`() {
            val result = freshShell().evaluate("java.time.LocalDate.of(2026, 8, 12).plusDays(5).toString()")
            assertEquals("2026-08-17", result.toString())
        }

        @Test
        fun `evaluates BigDecimal arithmetic`() {
            assertEquals("3.3", freshShell().evaluate("new BigDecimal('1.1') + new BigDecimal('2.2')").toString())
        }

        @Test
        fun `evaluates regex matching`() {
            assertEquals("true", freshShell().evaluate("'2026-08-12'.matches('\\\\d{4}-\\\\d{2}-\\\\d{2}')").toString())
        }
    }
}
