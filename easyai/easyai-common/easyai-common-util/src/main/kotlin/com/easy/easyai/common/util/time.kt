package com.easy.easyai.common.util

/**
 * Time this block of code and return the result and the time it took to execute.
 */
fun <T> time(block: () -> T): Pair<T, Long> {
    val start = System.currentTimeMillis()
    val result = block()
    val end = System.currentTimeMillis()
    return Pair(result, end - start)
}
