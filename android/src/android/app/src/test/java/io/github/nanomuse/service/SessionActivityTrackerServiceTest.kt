package io.github.nanomuse.service

import android.content.Context
import android.content.Intent
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionBadgeStore
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The real tracker with a mocked Context; no Android service, account or user data is touched. */
class SessionActivityTrackerServiceTest {
    private val session = "synthetic-service-turn"
    private val presence = "synthetic-service-presence"
    private val startRejected = AtomicBoolean(false)
    private val startWithoutComponent = AtomicBoolean(false)
    private val stopRejected = AtomicBoolean(false)
    private val starts = AtomicInteger(0)
    private lateinit var contextField: Field
    private var previousContext: Any? = null

    @Before fun setUp() {
        assertTrue(SessionActivityTracker.activeSessions.value.isEmpty())
        assertTrue(SessionActivityTracker.presentSessions.value.isEmpty())
        contextField = SessionActivityTracker::class.java.getDeclaredField("appContext").apply { isAccessible = true }
        previousContext = contextField.get(SessionActivityTracker)
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getString(anyInt())).thenReturn("synthetic status")
        val component = mock(android.content.ComponentName::class.java)
        val start = org.mockito.stubbing.Answer<android.content.ComponentName?> {
            starts.incrementAndGet()
            if (startRejected.get()) throw SecurityException("synthetic rejection")
            if (startWithoutComponent.get()) null else component
        }
        doAnswer(start).`when`(context).startService(any(Intent::class.java))
        doAnswer(start).`when`(context).startForegroundService(any(Intent::class.java))
        doAnswer {
            if (stopRejected.get()) throw SecurityException("synthetic rejection")
            true
        }.`when`(context).stopService(any(Intent::class.java))
        SessionActivityTracker.init(context)
    }

    @After fun tearDown() {
        startRejected.set(false)
        startWithoutComponent.set(false)
        stopRejected.set(false)
        runCatching {
            SessionActivityTracker.markReplyIncomplete(session)
            SessionActivityTracker.setInactive(session)
            SessionActivityTracker.setAbsent(presence)
            SessionActivityTracker.retryServiceRequest()
            SessionBadgeStore.clear(session)
        }
        contextField.set(SessionActivityTracker, previousContext)
    }

    @Test fun rejectedStartIsVisibleAndTheRealRunStillHasAStopIdentity() {
        startRejected.set(true)
        var cancellations = 0
        val turn = SessionActivityTracker.setActive(session) { cancellations++ }
        assertTrue(session in SessionActivityTracker.activeSessions.value)
        assertEquals(ServiceRequestIssue.START, SessionActivityTracker.serviceRequestIssue.value)
        SessionActivityTracker.cancelAllActiveStreams()
        assertEquals(1, cancellations)
        startRejected.set(false)
        SessionActivityTracker.retryServiceRequest()
        assertNull(SessionActivityTracker.serviceRequestIssue.value)
        SessionActivityTracker.markReplyIncomplete(session, turn)
        SessionActivityTracker.setInactive(session, turn)
        assertFalse(session in SessionActivityTracker.activeSessions.value)
        assertFalse(session in SessionBadgeStore.unread.value)
    }

    @Test fun startWithoutAComponentIsVisibleAndRetryDoesNotRepeatTheTurn() {
        startWithoutComponent.set(true)
        val turn = SessionActivityTracker.setActive(session)
        assertTrue(session in SessionActivityTracker.activeSessions.value)
        assertEquals(ServiceRequestIssue.START, SessionActivityTracker.serviceRequestIssue.value)
        val previousStarts = starts.get()
        startWithoutComponent.set(false)
        SessionActivityTracker.retryServiceRequest()
        assertEquals(previousStarts + 1, starts.get())
        assertNull(SessionActivityTracker.serviceRequestIssue.value)
        assertTrue(SessionActivityTracker.isCurrentRun(session, turn))
        SessionActivityTracker.markReplyIncomplete(session, turn)
        SessionActivityTracker.setInactive(session, turn)
    }

    @Test fun repeatedPresenceRetriesARejectedStart() {
        startRejected.set(true)
        SessionActivityTracker.setPresent(presence)
        assertTrue(presence in SessionActivityTracker.presentSessions.value)
        assertEquals(ServiceRequestIssue.START, SessionActivityTracker.serviceRequestIssue.value)
        val previousStarts = starts.get()
        startRejected.set(false)
        SessionActivityTracker.setPresent(presence)
        assertEquals(previousStarts + 1, starts.get())
        assertNull(SessionActivityTracker.serviceRequestIssue.value)
        SessionActivityTracker.setAbsent(presence)
    }

    @Test fun rejectedStopDoesNotPreventTrackerCleanupAndCanBeRetried() {
        var completions = 0
        SessionActivityTracker.setCompletionListener { id, _ -> if (id == session) completions++ }
        try {
            val turn = SessionActivityTracker.setActive(session)
            SessionActivityTracker.markReplyIncomplete(session, turn)
            stopRejected.set(true)
            SessionActivityTracker.setInactive(session, turn)
            assertFalse(session in SessionActivityTracker.activeSessions.value)
            assertFalse(session in SessionBadgeStore.unread.value)
            assertEquals(1, completions)
            assertEquals(ServiceRequestIssue.STOP, SessionActivityTracker.serviceRequestIssue.value)
            stopRejected.set(false)
            SessionActivityTracker.retryServiceRequest()
            assertNull(SessionActivityTracker.serviceRequestIssue.value)
            assertFalse(session in SessionActivityTracker.activeSessions.value)
            assertEquals(1, completions)
        } finally {
            SessionActivityTracker.setCompletionListener(null)
        }
    }
}
