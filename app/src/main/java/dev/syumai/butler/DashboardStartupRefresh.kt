package dev.syumai.butler

import java.time.Instant

/** One startup sequence on a dedicated worker. Every wait and request has a fixed bound. */
internal class DashboardStartupRefresh(
    private val networkReady: () -> Boolean,
    private val fetch: () -> DashboardSnapshot,
    private val dispatch: () -> Unit,
    private val onSnapshot: (DashboardSnapshot) -> Unit,
    private val onStatus: (String) -> Unit,
    private val wait: (Long) -> Unit = { Thread.sleep(it) },
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val now: () -> Instant = { Instant.now() },
) {
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    fun run(cachedAt: Instant?) {
        val deadline = elapsed() + 240_000
        var baseline = cachedAt
        val startedAt = now()
        fun active() = !cancelled && !Thread.currentThread().isInterrupted && elapsed() < deadline
        fun pause(delay: Long): Boolean {
            if (!active() || elapsed() + delay >= deadline) return false
            if (delay > 0) wait(delay)
            return active()
        }
        try {
            var dispatched = false
            for (delay in listOf(0L, 5_000L, 10_000L, 20_000L)) {
                if (!pause(delay)) break
                if (!networkReady()) { onStatus("network waiting"); continue }
                // Render the current KV snapshot before requesting a new one.
                runCatching { fetch() }.onSuccess { current ->
                    if (active()) {
                        onSnapshot(current)
                        if (baseline == null || current.generatedAt.toInstant().isAfter(baseline)) {
                            baseline = current.generatedAt.toInstant()
                        }
                    }
                }.onFailure { onStatus("initial fetch failed") }
                if (!active()) break
                if (runCatching { dispatch() }.isSuccess) {
                    onStatus("dispatch requested")
                    dispatched = true
                    break
                }
                onStatus("dispatch failed")
            }
            if (!dispatched || !active()) { onStatus("finished without dispatch"); return }
            val previous = baseline ?: startedAt
            for (delay in listOf(15_000L, 20_000L, 30_000L, 45_000L, 60_000L)) {
                if (!pause(delay)) break
                if (!networkReady()) continue
                val current = runCatching { fetch() }.getOrNull() ?: continue
                if (!active()) break
                if (current.generatedAt.toInstant().isAfter(previous)) {
                    onSnapshot(current)
                    onStatus("snapshot updated")
                    return
                }
            }
            onStatus("finished without newer snapshot")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
