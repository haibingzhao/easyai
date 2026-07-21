package com.easy.easyai.observability.annotation

/**
 * Classification of tracked operations for the [@Tracked] annotation.
 *
 * Different types help categorize operations in observability backends,
 * making it easier to filter and analyze traces.
 */
enum class TrackType {
    /** General-purpose operation (default). */
    CUSTOM,

    /** Data processing operation. */
    PROCESSING,

    /** Validation or verification step. */
    VALIDATION,

    /** Data transformation. */
    TRANSFORMATION,

    /** External service/API call. */
    EXTERNAL_CALL,

    /** Computation or calculation. */
    COMPUTATION
}
