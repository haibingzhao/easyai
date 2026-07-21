package com.easy.easyai.common.util


/**
 * Generate messages. Mainly used for fun
 */
fun interface MessageGenerator {

    /**
     * Generate a message
     */
    fun generate(): String
}

/**
 * Ordered list of messages
 */
interface MessageList : MessageGenerator {

    val messages: List<String>

    override fun generate(): String {
        return messages.random()
    }
}
