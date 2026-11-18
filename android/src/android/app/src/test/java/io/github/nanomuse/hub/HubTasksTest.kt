package io.github.nanomuse.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** `stop {call}` and `stop {conversation}` find the asker's own run here and nothing else. */
class HubTasksTest {
    @Test fun `the conversation is the one named, else one per device`() {
        val t = HubTasks()
        assertEquals("from-desk", t.conversationKey("desk", null))
        assertEquals("from-desk", t.conversationKey("desk", "  "))
        assertEquals("from-unknown", t.conversationKey("", null))
        assertEquals("notes", t.conversationKey("desk", " notes "))
    }

    @Test fun `the prompt carries the asker's language when the frame names one`() {
        assertEquals("do it", HubTasks.prompt("do it", prefix = null, language = null))
        assertEquals("From Desk.\n\ndo it", HubTasks.prompt("do it", prefix = "From Desk.", language = "  "))
        assertEquals(
            "From Desk.\n\nAnswer in the language tagged zh-Hans; the device that asked shows its screens in it.\n\ndo it",
            HubTasks.prompt("do it", prefix = "From Desk.", language = " zh-Hans "),
        )
        // a web console speaks for the person directly: no prefix, the language still counts
        assertEquals(
            "Answer in the language tagged fr; the device that asked shows its screens in it.\n\ndo it",
            HubTasks.prompt("do it", prefix = null, language = "fr"),
        )
    }

    @Test fun `stop by call id, by conversation, or by the sender's default conversation`() {
        val t = HubTasks()
        t.started("c1", "desk", "from-desk", "s1")
        t.started("c2", "desk", "notes", "s2")
        assertEquals("s1", t.find("desk", "c1", null))
        assertEquals("s2", t.find("desk", null, "notes"))
        assertEquals("s1", t.find("desk", null, null))
        assertEquals("s2", t.find("desk", "", "notes"))
    }

    @Test fun `another device cannot stop a run it did not ask for`() {
        val t = HubTasks()
        t.started("c1", "desk", "from-desk", "s1")
        assertNull(t.find("laptop", "c1", null))
        assertNull(t.find("laptop", null, "from-desk"))
        assertNull(t.find("desk", "nope", "nothing"))
    }

    @Test fun `a stopped task answers cancelled, a finished one does not`() {
        val t = HubTasks()
        t.started("c1", "desk", "from-desk", "s1")
        t.started("c2", "desk", "notes", "s2")
        t.markStopped("s1")
        assertTrue(t.finished("c1"))
        assertFalse(t.finished("c2"))
        assertFalse("forgotten after it finished", t.finished("c1"))
        assertNull(t.find("desk", "c1", null))
    }

    @Test fun `a delayed stop for a finished call never falls back to the next conversation run`() {
        val t = HubTasks()
        t.started("old", "desk", "from-desk", "old-session")
        t.finished("old")
        t.started("next", "desk", "from-desk", "new-session")
        assertNull(t.find("desk", "old", null))
        assertNull(t.find("desk", "missing", "from-desk"))
        assertEquals("new-session", t.find("desk", null, null))
    }

    @Test fun `a persistent stop must not mark another call sharing its session`() {
        val tasks = HubTasks()
        val own = Any()
        val other = Any()
        assertTrue(tasks.tryStart("own", "desk", "notes", "shared", own))
        assertTrue(tasks.tryStart("other", "laptop", "notes", "shared", other))
        assertTrue(tasks.stopExact("desk", "own", own) { session -> session == "shared" })
        assertFalse(tasks.stopExact("desk", "own", own) { error("a stop was already requested") })
        assertTrue(tasks.finishedExact("own", own))
        assertFalse("a different caller was never stopped", tasks.finishedExact("other", other))
    }

    @Test fun `verified stop requires the selected live call and successful cancellation`() {
        val tasks = HubTasks()
        val own = Any()
        assertTrue(tasks.tryStart("own", "desk", "notes", "shared", own))
        var attempts = 0
        val cancel: (String) -> Boolean = { attempts++; false }
        assertFalse(tasks.stopExact("desk", "", own, cancel))
        assertFalse(tasks.stopExact("desk", null, own, cancel))
        assertFalse(tasks.stopExact("desk", "missing", own, cancel))
        assertFalse(tasks.stopExact("laptop", "own", own, cancel))
        assertFalse(tasks.stopExact("desk", "own", Any(), cancel))
        assertEquals(0, attempts)
        assertFalse(tasks.stopExact("desk", "own", own, cancel))
        assertEquals(1, attempts)
        assertFalse(tasks.finishedExact("own", own))
        assertFalse(tasks.stopExact("desk", "own", own) { error("finished call must not be cancelled") })
    }

    @Test fun `a verified stop racing finish can only mark the selected live call`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val tasks = HubTasks()
                val own = Any()
                val other = Any()
                assertTrue(tasks.tryStart("own", "desk", "notes", "shared", own))
                assertTrue(tasks.tryStart("other", "laptop", "notes", "shared", other))
                val gate = CountDownLatch(1)
                val cancels = AtomicInteger(0)
                val stop = workers.submit<Boolean> {
                    gate.await()
                    tasks.stopExact("desk", "own", own) { cancels.incrementAndGet(); true }
                }
                val finish = workers.submit<Boolean> { gate.await(); tasks.finishedExact("own", own) }
                gate.countDown()
                val stopped = stop.get(5, TimeUnit.SECONDS)
                assertEquals(stopped, finish.get(5, TimeUnit.SECONDS))
                assertEquals(if (stopped) 1 else 0, cancels.get())
                assertFalse(tasks.finishedExact("other", other))
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun `a duplicate live id is refused without stealing the old registration`() {
        val tasks = HubTasks()
        val old = Any()
        val next = Any()
        assertFalse(tasks.tryStart(" ", "desk", "old", "old-session", old))
        assertTrue(tasks.tryStart("reused", "desk", "old", "old-session", old))
        assertFalse(tasks.tryStart("reused", "laptop", "new", "new-session", next))
        assertEquals("old-session", tasks.find("desk", "reused", null))
        assertNull(tasks.find("laptop", "reused", null))
        assertFalse(tasks.finishedExact("reused", next))
        assertEquals("old-session", tasks.find("desk", "reused", null))
        assertFalse(tasks.finishedExact("reused", old))
    }

    @Test fun `delayed stop and cleanup cannot affect a replacement with the same id`() {
        val tasks = HubTasks()
        val old = Any()
        val next = Any()
        assertTrue(tasks.tryStart("reused", "desk", "old", "old-session", old))
        assertFalse(tasks.finishedExact("reused", old))
        assertTrue(tasks.tryStart("reused", "desk", "new", "new-session", next))
        assertFalse(tasks.finishedExact("reused", old))
        assertFalse(tasks.stopExact("desk", "reused", old) { error("an old stop must not cancel the replacement") })
        assertEquals("new-session", tasks.find("desk", "reused", null))
        assertTrue(tasks.stopExact("desk", "reused", next) { it == "new-session" })
        assertTrue(tasks.finishedExact("reused", next))
    }

    @Test fun `parallel reservations racing for the same id admit only one`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val tasks = HubTasks()
                val gate = CountDownLatch(1)
                val owners = listOf(Any(), Any())
                val starts = owners.mapIndexed { index, owner -> workers.submit<Boolean> {
                    gate.await()
                    tasks.tryStart("same", "desk$index", "conversation", "session$index", owner)
                } }
                gate.countDown()
                val results = starts.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it })
                val winner = results.indexOf(true)
                assertEquals("session$winner", tasks.find("desk$winner", "same", null))
                assertFalse(tasks.finishedExact("same", owners[1 - winner]))
                assertEquals("session$winner", tasks.find("desk$winner", "same", null))
                assertFalse(tasks.finishedExact("same", owners[winner]))
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun `legacy stop continues to mark every run in the stopped session`() {
        val tasks = HubTasks()
        tasks.started("own", "desk", "notes", "shared")
        tasks.started("other", "laptop", "notes", "shared")
        assertTrue(tasks.stop("desk", "own", null) { true })
        assertTrue(tasks.finished("own"))
        assertTrue(tasks.finished("other")) // compatibility control, not a safety guarantee
    }

    @Test fun `conversation lookup selects only the requesting device's run`() {
        val t = HubTasks()
        t.started("other", "laptop", "notes", "other-session")
        t.started("own", "desk", "notes", "own-session")
        assertEquals("own-session", t.find("desk", null, "notes"))
        assertNull(t.find("desk", "other", "notes"))
    }
}
