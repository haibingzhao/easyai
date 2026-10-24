package com.easy.easyai.tools.shell

import com.easy.easyai.common.util.destroyProcessTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Probes the user's login shell environment once and caches the result.
 *
 * When Spring Boot is launched from an IDE or systemd, the process inherits
 * an incomplete environment (e.g. missing user-local PATH entries like
 * `~/.local/bin`, `nvm`, `sdkman`). This probe starts an interactive login
 * shell, runs `env -0` to dump all variables, and caches the result so
 * subsequent tool executions see the full user environment.
 *
 * The probe is best-effort: if it fails or times out, an empty map is
 * returned and the caller falls back to the JVM's inherited environment.
 */
object ShellEnvProbe {

    private val logger = LoggerFactory.getLogger(ShellEnvProbe::class.java)
    private val probeMutex = Mutex()

    @Volatile
    private var cachedEnv: Map<String, String>? = null

    /** Timeout for the shell env probe subprocess. */
    private const val PROBE_TIMEOUT_SECONDS = 5L

    /**
     * Returns the user's login shell environment variables.
     *
     * The result is cached after the first successful probe.
     * Returns an empty map on Windows or if the probe fails.
     */
    suspend fun probe(): Map<String, String> {
        cachedEnv?.let { return it }
        return probeMutex.withLock {
            cachedEnv?.let { return@withLock it }
            val env = doProbe()
            coroutineContext.ensureActive()
            cachedEnv = env
            env
        }
    }

    /** Clears the cached environment. Primarily for testing. */
    internal fun reset() {
        cachedEnv = null
    }

    private suspend fun doProbe(): Map<String, String> = withContext(Dispatchers.IO) {
        // Windows doesn't support the bash login-shell probe; fall back to JVM env.
        if (System.getProperty("os.name")?.lowercase()?.contains("windows") == true) {
            logger.debug("Skipping shell env probe on Windows")
            return@withContext emptyMap()
        }

        val shell = System.getenv("SHELL") ?: "/bin/sh"
        try {
            val process = ProcessBuilder(shell, "-ilc", "env -0")
                .redirectErrorStream(true)
                .start()

            // Read output on a daemon thread so that background processes spawned
            // by the login shell (gpg-agent, nvm helpers, etc.) cannot block us
            // indefinitely by holding the stdout pipe open.
            val outputRef = AtomicReference("")
            val readerThread = Thread {
                outputRef.set(process.inputStream.bufferedReader().readText())
            }.apply {
                isDaemon = true
                start()
            }

            val exited = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                destroyProcessTree(process)
                readerThread.join(1000)
                logger.warn("Shell env probe timed out for shell: {}", shell)
                return@withContext emptyMap()
            }

            // Process exited normally; wait briefly for reader thread to finish
            readerThread.join(2000)

            if (process.exitValue() != 0) {
                logger.warn("Shell env probe exited with code {} for shell: {}", process.exitValue(), shell)
                return@withContext emptyMap()
            }
            val output = outputRef.get()
            val env = output.split('\u0000')
                .asSequence()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            if (env.isNotEmpty()) {
                logger.debug("Shell env probe loaded {} vars from shell: {}", env.size, shell)
            }
            env
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Shell env probe failed for shell {}: {}", shell, e.message)
            emptyMap()
        }
    }
}
