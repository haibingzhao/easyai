package com.easy.easyai.swarm.dag

import com.easy.easyai.swarm.model.SwarmTask
import com.easy.easyai.swarm.model.SwarmTaskStatus
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DagAlgorithmsTest {

    private fun task(id: String, dependsOn: List<String> = emptyList()) = SwarmTask(
        id = id,
        promptTemplate = "prompt for $id",
        dependsOn = dependsOn
    )

    @Nested
    inner class `validateDag` {
        @Test
        fun `empty task list is valid`() {
            DagAlgorithms.validateDag(emptyList())
        }

        @Test
        fun `single task is valid`() {
            DagAlgorithms.validateDag(listOf(task("a")))
        }

        @Test
        fun `linear chain is valid`() {
            DagAlgorithms.validateDag(listOf(
                task("a"),
                task("b", listOf("a")),
                task("c", listOf("b"))
            ))
        }

        @Test
        fun `diamond shape is valid`() {
            DagAlgorithms.validateDag(listOf(
                task("a"),
                task("b", listOf("a")),
                task("c", listOf("a")),
                task("d", listOf("b", "c"))
            ))
        }

        @Test
        fun `self-loop detected`() {
            val ex = assertFailsWith<IllegalStateException> {
                DagAlgorithms.validateDag(listOf(task("a", listOf("a"))))
            }
            assertTrue(ex.message!!.contains("cycle"))
        }

        @Test
        fun `two-node cycle detected`() {
            val ex = assertFailsWith<IllegalStateException> {
                DagAlgorithms.validateDag(listOf(
                    task("a", listOf("b")),
                    task("b", listOf("a"))
                ))
            }
            assertTrue(ex.message!!.contains("cycle"))
        }

        @Test
        fun `three-node cycle detected`() {
            val ex = assertFailsWith<IllegalStateException> {
                DagAlgorithms.validateDag(listOf(
                    task("a", listOf("c")),
                    task("b", listOf("a")),
                    task("c", listOf("b"))
                ))
            }
            assertTrue(ex.message!!.contains("cycle"))
        }

        @Test
        fun `unknown dependency rejected`() {
            val ex = assertFailsWith<IllegalArgumentException> {
                DagAlgorithms.validateDag(listOf(task("a", listOf("nonexistent"))))
            }
            assertTrue(ex.message!!.contains("unknown task"))
        }
    }

    @Nested
    inner class `topologicalLayers` {
        @Test
        fun `single task produces one layer`() {
            val layers = DagAlgorithms.topologicalLayers(listOf(task("a")))
            assertEquals(1, layers.size)
            assertEquals(listOf("a"), layers[0])
        }

        @Test
        fun `independent tasks in same layer`() {
            val layers = DagAlgorithms.topologicalLayers(listOf(
                task("a"),
                task("b"),
                task("c")
            ))
            assertEquals(1, layers.size)
            assertEquals(3, layers[0].size)
            assertTrue(layers[0].containsAll(listOf("a", "b", "c")))
        }

        @Test
        fun `linear chain produces sequential layers`() {
            val layers = DagAlgorithms.topologicalLayers(listOf(
                task("a"),
                task("b", listOf("a")),
                task("c", listOf("b"))
            ))
            assertEquals(3, layers.size)
            assertEquals(listOf("a"), layers[0])
            assertEquals(listOf("b"), layers[1])
            assertEquals(listOf("c"), layers[2])
        }

        @Test
        fun `diamond produces correct layers`() {
            val layers = DagAlgorithms.topologicalLayers(listOf(
                task("a"),
                task("b", listOf("a")),
                task("c", listOf("a")),
                task("d", listOf("b", "c"))
            ))
            assertEquals(3, layers.size)
            assertEquals(listOf("a"), layers[0])
            assertTrue(layers[1].containsAll(listOf("b", "c")))
            assertEquals(listOf("d"), layers[2])
        }

        @Test
        fun `investment committee DAG produces 4 layers`() {
            val layers = DagAlgorithms.topologicalLayers(listOf(
                task("task-market"),
                task("task-fundamentals"),
                task("deliberation-research", listOf("task-market", "task-fundamentals")),
                task("deliberation-risk", listOf("deliberation-research")),
                task("task-final", listOf("deliberation-risk"))
            ))
            assertEquals(4, layers.size)
            // Layer 0: parallel analysis
            assertTrue(layers[0].containsAll(listOf("task-market", "task-fundamentals")))
            // Layer 1: research deliberation
            assertEquals(listOf("deliberation-research"), layers[1])
            // Layer 2: risk deliberation
            assertEquals(listOf("deliberation-risk"), layers[2])
            // Layer 3: final decision
            assertEquals(listOf("task-final"), layers[3])
        }
    }

    @Nested
    inner class `resolveDependencies` {
        @Test
        fun `completed task removes from blockedBy`() {
            val tasks = listOf(
                task("a"),
                task("b", listOf("a")).apply { blockedBy = listOf("a") },
                task("c", listOf("a")).apply { blockedBy = listOf("a") }
            )

            DagAlgorithms.resolveDependencies(tasks, "a", failed = false)

            assertTrue(tasks[1].blockedBy.isEmpty())
            assertTrue(tasks[2].blockedBy.isEmpty())
        }

        @Test
        fun `failed task blocks downstream transitively`() {
            val tasks = listOf(
                task("a"),
                task("b", listOf("a")),
                task("c", listOf("b"))
            )

            DagAlgorithms.resolveDependencies(tasks, "a", failed = true)

            assertEquals(SwarmTaskStatus.BLOCKED, tasks[1].status)
            assertEquals(SwarmTaskStatus.BLOCKED, tasks[2].status)
        }

        @Test
        fun `failed task blocks only direct and transitive dependents`() {
            val tasks = listOf(
                task("a"),
                task("b", listOf("a")),
                task("c") // Independent, should not be blocked
            )

            DagAlgorithms.resolveDependencies(tasks, "a", failed = true)

            assertEquals(SwarmTaskStatus.BLOCKED, tasks[1].status)
            assertEquals(SwarmTaskStatus.PENDING, tasks[2].status)
        }
    }
}
