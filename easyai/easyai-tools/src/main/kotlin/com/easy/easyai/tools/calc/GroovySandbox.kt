package com.easy.easyai.tools.calc

import groovy.lang.GroovyShell
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.SecureASTCustomizer

/**
 * Groovy script security sandbox.
 *
 * Uses [SecureASTCustomizer] at compile time to disallow access to dangerous packages
 * (IO / network / system processes), ensuring LLM-generated calculation scripts
 * can only perform math and date operations in memory.
 */
internal object GroovySandbox {

    fun createSecureShell(): GroovyShell {
        val secure = SecureASTCustomizer().apply {
            // Disallowed import packages (wildcard matches sub-packages)
            disallowedImports = listOf(
                "java.io.**",
                "java.net.**",
                "java.lang.reflect.**",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader",
                "javax.script.**",
                "javax.management.**"
            )
            // Disallowed static import classes
            disallowedStaticImports = listOf(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.Thread"
            )
            // Disallowed static star imports
            disallowedStaticStarImports = listOf(
                "java.lang.System",
                "java.lang.Runtime"
            )
        }
        val config = CompilerConfiguration().apply {
            addCompilationCustomizers(secure)
        }
        return GroovyShell(config)
    }
}
