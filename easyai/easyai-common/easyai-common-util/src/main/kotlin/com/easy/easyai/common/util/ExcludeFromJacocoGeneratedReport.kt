package com.easy.easyai.common.util

/**
 * This annotation is used to exclude a method from the Jacoco generated report. By
 * containing the word "Generated" it excludes classes or methods.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CLASS
)
annotation class ExcludeFromJacocoGeneratedReport(val reason: String)
