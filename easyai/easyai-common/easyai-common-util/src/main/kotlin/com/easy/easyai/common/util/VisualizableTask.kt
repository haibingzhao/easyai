package com.easy.easyai.common.util

/**
 * Interface representing a task.
 * Implementations can be visualized as a progress bar.
 */
interface VisualizableTask {

    /**
     * Name of the task
     */
    val name: String

    /**
     * Current step of the task, out of total steps
     */
    val current: Int

    /**
     * Total steps in the task
     */
    val total: Int

    /**
     * Create a progress bar as a string
     */
    fun createProgressBar(length: Int = 50): String {
        val percent = (current * 100.0 / total).toInt()
        val completed = (length * current / total)

        return buildString {
            append("$name - [")
            repeat(length) { i ->
                append(
                    when {
                        i < completed -> "="
                        i == completed -> ">"
                        else -> " "
                    }
                )
            }
            append("] ")
            append("%3d%%".format(percent))
            append(" (%d/%d)".format(current, total))
        }
    }

    companion object {
        operator fun invoke(
            name: String,
            current: Int,
            total: Int
        ): VisualizableTask = object : VisualizableTask {
            override val name: String = name
            override val current: Int = current
            override val total: Int = total
        }
    }
}
