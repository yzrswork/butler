package dev.syumai.butler

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

class DashboardStartupRefreshTest {
    private val at = OffsetDateTime.parse("2026-09-30T12:00:00+09:00")
    private fun snapshot(seconds: Long = 0) = DashboardSnapshot(
        dataDate = "2026-09-30", generatedAt = at.plusSeconds(seconds), notes = 1, newNotes = 0,
        commits = 2, commitFrom = at.minusDays(1).toString(), prNumbers = emptyList(), prCount = 0,
        projects = 1, activity = emptyList(), featured = null, active = emptyList(), activeTotal = 0,
    )

    @Test fun currentKvIsDeliveredBeforeDispatchThenNewTimestampIsDelivered() {
        val events = mutableListOf<String>()
        var calls = 0
        var elapsed = 0L
        val refresh = DashboardStartupRefresh(
            networkReady = { true }, fetch = { events += "get"; snapshot(if (calls++ == 0) 0 else 60) },
            dispatch = { events += "dispatch" }, onSnapshot = { events += "display:${it.generatedAt}" },
            onStatus = {}, wait = { elapsed += it }, elapsed = { elapsed }, now = { at.toInstant() },
        )
        refresh.run(at.toInstant())
        assertEquals(listOf("get", "display:$at", "dispatch", "get", "display:${at.plusSeconds(60)}"), events)
        assertEquals(15_000L, elapsed)
    }

    @Test fun networkNotReadyStopsWithoutRequests() {
        var readinessChecks = 0
        val refresh = DashboardStartupRefresh(
            networkReady = { readinessChecks++; false }, fetch = { fail("unexpected GET"); snapshot() },
            dispatch = { fail("unexpected dispatch") }, onSnapshot = { fail("unexpected replacement") },
            onStatus = {}, wait = {},
        )
        refresh.run(at.toInstant())
        assertEquals(4, readinessChecks)
    }

    @Test fun dispatchFailureIsBoundedAndNeverClearsDisplayedSnapshot() {
        var dispatches = 0
        val displayed = mutableListOf<DashboardSnapshot>()
        val original = snapshot()
        displayed += original
        DashboardStartupRefresh(
            networkReady = { true }, fetch = { throw IOException("offline") },
            dispatch = { dispatches++; throw IOException("dispatch unavailable") },
            onSnapshot = { displayed += it }, onStatus = {}, wait = {},
        ).run(at.toInstant())
        assertEquals(4, dispatches)
        assertEquals(listOf(original), displayed)
    }

    @Test fun unchangedSnapshotHasFiveBoundedChecksAndOneDispatch() {
        var gets = 0
        var dispatches = 0
        var elapsed = 0L
        val displayed = mutableListOf<DashboardSnapshot>()
        DashboardStartupRefresh(
            networkReady = { true }, fetch = { gets++; snapshot() }, dispatch = { dispatches++ },
            onSnapshot = { displayed += it }, onStatus = {}, wait = { elapsed += it }, elapsed = { elapsed },
        ).run(at.toInstant())
        assertEquals(6, gets)
        assertEquals(1, dispatches)
        assertEquals(1, displayed.size)
        assertEquals(170_000L, elapsed)
    }

    @Test fun failedFollowupFetchRetainsInitialSnapshotAndStops() {
        var gets = 0
        val displayed = mutableListOf<DashboardSnapshot>()
        DashboardStartupRefresh(
            networkReady = { true }, fetch = { if (gets++ == 0) snapshot() else throw IOException("offline") },
            dispatch = {}, onSnapshot = { displayed += it }, onStatus = {}, wait = {},
        ).run(at.toInstant())
        assertEquals(6, gets)
        assertEquals(listOf(snapshot()), displayed)
    }

    @Test fun cancellationStopsQueuedFollowups() {
        var gets = 0
        lateinit var refresh: DashboardStartupRefresh
        refresh = DashboardStartupRefresh(
            networkReady = { true }, fetch = { gets++; snapshot() }, dispatch = {},
            onSnapshot = {}, onStatus = {}, wait = { refresh.cancel() },
        )
        refresh.run(at.toInstant())
        assertEquals(1, gets)
    }

    @Test fun totalDeadlineStopsEvenWithSlowRequests() {
        var elapsed = 0L
        var dispatches = 0
        DashboardStartupRefresh(
            networkReady = { true }, fetch = { elapsed += 8_000; snapshot() },
            dispatch = { elapsed += 8_000; dispatches++; if (dispatches < 4) throw IOException() },
            onSnapshot = {}, onStatus = {}, wait = { elapsed += it }, elapsed = { elapsed },
        ).run(at.toInstant())
        assertTrue(elapsed <= 248_000)
        assertEquals(4, dispatches)
    }
}
