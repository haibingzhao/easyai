package com.easy.easyai.common.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles


/**
 * Convenient and efficient way to provide a logger for a specific class.
 * Useful when we don't want to hold a field--for example, in an entity or utility function.
 */
inline fun <reified T> loggerFor(): Logger = LoggerFactory.getLogger(T::class.java)


/**
 * Provide a logger for any class.
 */
@Deprecated("Use the inline version for better performance", ReplaceWith("loggerFor<Type>()"))
fun logger(): Logger {
    val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        .walk { frames ->
            frames
                .filter { !it.className.contains("GetLogger") }
                .findFirst()
                .map { it.declaringClass }
                .orElse(MethodHandles.lookup().lookupClass())
        }
    return LoggerFactory.getLogger(callerClass)
}
