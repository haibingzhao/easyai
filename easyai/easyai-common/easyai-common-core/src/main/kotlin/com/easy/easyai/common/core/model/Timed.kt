package com.easy.easyai.common.core.model

import java.time.Duration

/**
 * Enables consistent handling of durations across different types of data.
 */
interface Timed {

    /**
     * How long this process has taken
     */
    val runningTime: Duration
}
