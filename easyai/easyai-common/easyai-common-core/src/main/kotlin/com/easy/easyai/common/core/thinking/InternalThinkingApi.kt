package com.easy.easyai.common.core.thinking

/**
 * Marks APIs that are internal thinking processing utilities.
 *
 * These APIs are intended for use by converters and internal processing
 * components, not end-user code. Use with caution as they may change
 * without notice.
 */
@RequiresOptIn(
    message = "This is an internal thinking extraction API. Use with caution as it may change without notice.",
    level = RequiresOptIn.Level.ERROR
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalThinkingApi
