package com.easy.easyai.swarm.dag

import com.easy.easyai.swarm.dag.DagAlgorithms.resolveDependencies
import com.easy.easyai.swarm.dag.DagAlgorithms.topologicalLayers
import com.easy.easyai.swarm.dag.DagAlgorithms.validateDag
import com.easy.easyai.swarm.model.SwarmTask
import com.easy.easyai.swarm.model.SwarmTaskStatus

/**
 * Pure-function DAG algorithms for swarm task scheduling.
 *
 * Ported from Vibe-Trading `task_store.py`:
 * - [validateDag]: DFS cycle detection
 * - [topologicalLayers]: Kahn's algorithm for layer-based scheduling
 * - [resolveDependencies]: Remove completed task from downstream blockedBy lists
 */
object DagAlgorithms {

    /**
     * Validate that the task graph is a DAG (no cycles).
     * Uses DFS-based cycle detection (white-gray-black coloring).
     *
     * @throws IllegalStateException if a cycle is detected
     */
    fun validateDag(tasks: List<SwarmTask>) {
        val adjacency = buildAdjacencyMap(tasks)
        val taskIds = tasks.map { it.id }.toSet()

        // Validate: all depends_on references must exist
        for (task in tasks) {
            for (dep in task.dependsOn) {
                require(dep in taskIds) {
                    "Task '${task.id}' depends on unknown task '$dep'"
                }
            }
        }

        // DFS cycle detection
        val color = mutableMapOf<String, Color>()
        tasks.forEach { color[it.id] = Color.WHITE }

        fun dfs(nodeId: String, path: MutableList<String>) {
            color[nodeId] = Color.GRAY
            path.add(nodeId)

            for (neighbor in adjacency[nodeId].orEmpty()) {
                when (color[neighbor]) {
                    Color.GRAY -> {
                        val cycleStart = path.indexOf(neighbor)
                        val cycle = path.subList(cycleStart, path.size) + neighbor
                        throw IllegalStateException(
                            "DAG cycle detected: ${cycle.joinToString(" → ")}"
                        )
                    }
                    Color.WHITE -> dfs(neighbor, path)
                    Color.BLACK -> { /* already fully processed */ }
                    null -> { /* unknown node, handled by validation above */ }
                }
            }

            path.removeLast()
            color[nodeId] = Color.BLACK
        }

        for (task in tasks) {
            if (color[task.id] == Color.WHITE) {
                dfs(task.id, mutableListOf())
            }
        }
    }

    /**
     * Compute topological layers using Kahn's algorithm.
     * Tasks within the same layer have no dependencies on each other and can execute in parallel.
     * Layers are executed sequentially (layer 0 first, then layer 1, etc.).
     *
     * @return List of layers, where each layer is a list of task IDs that can run in parallel.
     * @throws IllegalStateException if a cycle is detected (Kahn's detects cycles when remaining nodes have no zero-indegree)
     */
    fun topologicalLayers(tasks: List<SwarmTask>): List<List<String>> {
        val adjacency = buildAdjacencyMap(tasks)

        // Compute in-degree for each task
        val inDegree = mutableMapOf<String, Int>()
        tasks.forEach { inDegree[it.id] = it.dependsOn.size }

        val layers = mutableListOf<List<String>>()
        var processed = 0

        while (processed < tasks.size) {
            // Find all tasks with in-degree 0 (no pending dependencies)
            val layer = inDegree.filter { it.value == 0 }.keys.toList()

            if (layer.isEmpty()) {
                // Remaining tasks all have dependencies — cycle exists
                val remaining = inDegree.keys.toList()
                throw IllegalStateException(
                    "DAG cycle detected: remaining tasks have unresolved dependencies: $remaining"
                )
            }

            layers.add(layer)
            processed += layer.size

            // Remove processed tasks from in-degree map and decrement dependents
            for (nodeId in layer) {
                inDegree.remove(nodeId)
                for (dependent in adjacency[nodeId].orEmpty()) {
                    inDegree[dependent]?.let { inDegree[dependent] = it - 1 }
                }
            }
        }

        return layers
    }

    /**
     * After a task completes, remove it from downstream tasks' blockedBy lists.
     * Also detects blocked tasks (upstream failed → downstream should be BLOCKED).
     *
     * @param tasks All tasks in the run.
     * @param completedTaskId The ID of the task that just completed (or failed).
     * @param failed If true, mark all direct and transitive dependents as BLOCKED.
     * @return Updated list of tasks (mutated in place).
     */
    fun resolveDependencies(
        tasks: List<SwarmTask>,
        completedTaskId: String,
        failed: Boolean = false
    ): List<SwarmTask> {
        val adjacency = buildAdjacencyMap(tasks)

        if (failed) {
            // Cascade: mark all transitive dependents as BLOCKED
            val toBlock = ArrayDeque<String>()
            adjacency[completedTaskId]?.forEach { toBlock.add(it) }

            while (toBlock.isNotEmpty()) {
                val taskId = toBlock.removeFirst()
                val task = tasks.find { it.id == taskId } ?: continue
                if (task.status == SwarmTaskStatus.PENDING) {
                    task.status = SwarmTaskStatus.BLOCKED
                    task.blockedBy = listOf(completedTaskId)
                    // Propagate to downstream
                    adjacency[taskId]?.forEach { toBlock.add(it) }
                }
            }
        } else {
            // Remove completed task from dependents' blockedBy
            for (task in tasks) {
                if (completedTaskId in task.blockedBy) {
                    task.blockedBy -= completedTaskId
                }
            }
        }

        return tasks
    }

    /**
     * Build adjacency map: taskId → list of tasks that depend on it.
     * Used for both topological sort and dependency resolution.
     */
    private fun buildAdjacencyMap(tasks: List<SwarmTask>): Map<String, List<String>> {
        val map = mutableMapOf<String, MutableList<String>>()
        tasks.forEach { map[it.id] = mutableListOf() }

        for (task in tasks) {
            for (dep in task.dependsOn) {
                map.getOrPut(dep) { mutableListOf() }.add(task.id)
            }
        }

        return map
    }

    private enum class Color { WHITE, GRAY, BLACK }
}
