package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.watch.WatchProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bindings for `easyai.watch.*`, mapped onto the core [WatchProperties].
 *
 * Defaults are read from a core instance instead of being restated: a literal here silently loses
 * to the core default the moment one side is tuned, and the binding always wins because the
 * auto-configuration rebuilds the core type from these values. A core limit left out of this class
 * becomes unconfigurable.
 */
@ConfigurationProperties(prefix = "easyai.watch")
class WatchPropertiesBinding {
    private val coreDefaults = WatchProperties()

    /** Deployment-wide kill switch; false stops planning and fails no existing row. */
    var enabled: Boolean = coreDefaults.enabled

    /** Hard cap on ACTIVE definitions per user. */
    var maxActivePerUser: Int = coreDefaults.maxActivePerUser

    /** Wall-clock budget of one watch run; the task runner parks the row TIMED_OUT. */
    var runTimeoutMs: Long = coreDefaults.runTimeoutMs

    /** Agent-loop iteration cap for a normal check. */
    var maxIterations: Int = coreDefaults.maxIterations

    /** Agent-loop iteration cap for an expiry review. */
    var expiryReviewMaxIterations: Int = coreDefaults.expiryReviewMaxIterations

    /** How many times an expiry review may renew a watch without the user asking. */
    var maxAutoExtensions: Int = coreDefaults.maxAutoExtensions

    /** Furthest a renewal may push a deadline, in days. */
    var maxExtensionHorizonDays: Long = coreDefaults.maxExtensionHorizonDays

    /** How long a claimed expiry review waits for its retry before the planner may re-claim it. */
    var expiryReviewGuardMs: Long = coreDefaults.expiryReviewGuardMs

    /** A one-shot firing later than this is reviewed instead of checked. */
    var catchUpWindowMs: Long = coreDefaults.catchUpWindowMs

    /** Guard between claiming a one-shot and the handler writing its final due instant. */
    var oneShotRetryGuardMs: Long = coreDefaults.oneShotRetryGuardMs

    /** First delay after a failed run; doubled per consecutive failure. */
    var failureBackoffBaseMs: Long = coreDefaults.failureBackoffBaseMs

    /** Ceiling of the failure backoff. */
    var failureBackoffMaxMs: Long = coreDefaults.failureBackoffMaxMs

    /** Consecutive failures after which a watch is paused and the user is notified once. */
    var maxFailuresBeforePause: Int = coreDefaults.maxFailuresBeforePause

    /** Max automatic watch admissions a single user may have per minute on this host. */
    var perUserPerMinuteLimit: Int = coreDefaults.perUserPerMinuteLimit

    fun toProperties(): WatchProperties = WatchProperties(
        enabled = enabled,
        maxActivePerUser = maxActivePerUser,
        runTimeoutMs = runTimeoutMs,
        maxIterations = maxIterations,
        expiryReviewMaxIterations = expiryReviewMaxIterations,
        maxAutoExtensions = maxAutoExtensions,
        maxExtensionHorizonDays = maxExtensionHorizonDays,
        expiryReviewGuardMs = expiryReviewGuardMs,
        catchUpWindowMs = catchUpWindowMs,
        oneShotRetryGuardMs = oneShotRetryGuardMs,
        failureBackoffBaseMs = failureBackoffBaseMs,
        failureBackoffMaxMs = failureBackoffMaxMs,
        maxFailuresBeforePause = maxFailuresBeforePause,
        perUserPerMinuteLimit = perUserPerMinuteLimit
    )
}
