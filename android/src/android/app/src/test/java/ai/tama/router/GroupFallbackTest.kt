package ai.tama.router

import org.junit.Assert.*
import org.junit.Test

class GroupFallbackTest {
    @Test fun groupOrderUsesEntryIdsAndDoesNotSkipTheFirstMemberWhenActiveIsOutside() {
        val members = listOf("a", "b", "c")
        assertEquals(listOf("c", "a"), GroupFallback.candidates(members, "b"))
        assertEquals(listOf("b", "c"), GroupFallback.candidates(members, "a"))
        assertEquals(members, GroupFallback.candidates(members, "outside"))
        assertEquals(members, GroupFallback.candidates(members, null))
        assertEquals(emptyList<String>(), GroupFallback.candidates(listOf("a"), "a"))
        assertEquals(listOf("b"), GroupFallback.candidates(listOf("a", "a", "", "b", "b"), "a"))
    }

    @Test fun hiddenDisabledUncredentialedOrChangedCandidatesCannotBeSent() {
        assertTrue(GroupFallback.eligible(false, true, true, true, authorized = true))
        assertFalse(GroupFallback.eligible(true, true, true, true, authorized = true))
        assertFalse(GroupFallback.eligible(false, false, true, true, authorized = true))
        assertFalse(GroupFallback.eligible(false, true, false, true, authorized = true))
        assertFalse(GroupFallback.eligible(false, true, true, false, authorized = true))
        assertFalse(GroupFallback.eligible(false, true, true, true, authorized = false))
        assertFalse(GroupFallback.eligible(false, true, true, true, authorized = true, stillMember = false))
        assertFalse(GroupFallback.eligible(false, true, true, true, authorized = true, sameModel = false))
        assertFalse(GroupFallback.eligible(false, true, true, true, authorized = true, sameRoute = false))
        assertFalse(GroupFallback.eligible(false, true, true, true, authorized = true, sameAccount = false))
    }

    @Test fun onlyACleanNonemptyCompletionCanPersistASwitch() {
        listOf("stop", "end_turn", "completed").forEach {
            assertTrue(GroupFallback.completed(it, hasText = true, hasTools = false))
            assertTrue(GroupFallback.completed(it, hasText = false, hasTools = true))
            assertFalse(GroupFallback.completed(it, hasText = false, hasTools = false))
        }
        listOf("tool_calls", "tool_use", "function_call").forEach {
            assertFalse(GroupFallback.completed(it, hasText = true, hasTools = false))
            assertTrue(GroupFallback.completed(it, hasText = false, hasTools = true))
            assertFalse(GroupFallback.completed(it, hasText = false, hasTools = false))
        }
        listOf(null, "length", "max_tokens", "content_filter", "safety", "error").forEach {
            assertFalse(GroupFallback.completed(it, hasText = true, hasTools = false))
            assertFalse(GroupFallback.mayExecuteTools(it))
        }
        listOf("stop", "end_turn", "tool_calls", "tool_use", "function_call", "completed").forEach {
            assertTrue(GroupFallback.mayExecuteTools(it))
        }
    }

    @Test fun onlyAFinalCleanTextAnswerConfirmsTheRoute() {
        assertTrue(GroupFallback.confirmsRoute("stop", hasText = true, hasTools = false))
        assertTrue(GroupFallback.confirmsRoute("end_turn", hasText = true, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute("tool_calls", hasText = true, hasTools = true))
        assertFalse(GroupFallback.confirmsRoute("tool_use", hasText = false, hasTools = true))
        // A missing tool-call body must not turn a tool finish into a final answer.
        assertFalse(GroupFallback.confirmsRoute("tool_calls", hasText = true, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute("tool_use", hasText = true, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute("function_call", hasText = true, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute("stop", hasText = false, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute("length", hasText = true, hasTools = false))
        assertFalse(GroupFallback.confirmsRoute(null, hasText = true, hasTools = false))
    }

    @Test fun groupSwitchDoesNotForwardAnUnknownOrSizeFailure() {
        assertTrue(GroupFallback.canSwitch(AutoModelFailure.Decision.Cooldown(ModelHealth.Failure.RATE_LIMIT), false))
        assertTrue(GroupFallback.canSwitch(AutoModelFailure.Decision.Cooldown(ModelHealth.Failure.NO_CREDIT), false))
        assertTrue(GroupFallback.canSwitch(AutoModelFailure.Decision.Cooldown(ModelHealth.Failure.NETWORK), false))
        assertTrue(GroupFallback.canSwitch(AutoModelFailure.Decision.UnsupportedTools, false))
        assertFalse(GroupFallback.canSwitch(null, false))
        assertFalse(GroupFallback.canSwitch(AutoModelFailure.Decision.Cooldown(ModelHealth.Failure.REQUEST_TOO_LARGE), false))
        assertFalse(GroupFallback.canSwitch(AutoModelFailure.Decision.Cooldown(ModelHealth.Failure.RATE_LIMIT), true))
    }

    @Test fun failedAndInterruptedAttemptsAreNeverPersistedOnlyFinishedActiveRouteIs() {
        val state = FallbackCommit()
        val saved = mutableListOf<FallbackCommit.Attempt>()
        fun record(finished: Boolean, entry: String?, group: String?, sameClient: Boolean = true,
            sameRoute: Boolean = true, sameSession: Boolean = true) {
            state.confirmed(finished, entry, group, sameClient, sameRoute, sameSession)?.let(saved::add)
        }
        state.switched("b", "group")
        record(false, "b", "group") // failed or interrupted stream
        assertTrue(saved.isEmpty())
        state.switched("c", "group") // a further candidate replaces the failed one
        record(false, "c", "group")
        record(true, "b", "group") // stale entry
        record(true, "c", "other-group") // person selected a different group
        record(true, "c", "group", sameClient = false)
        record(true, "c", "group", sameRoute = false) // account, credentials or endpoint changed
        record(true, "c", "group", sameSession = false) // the person navigated elsewhere
        assertTrue(saved.isEmpty())
        record(true, "c", "group")
        record(true, "c", "group") // completion is idempotent
        assertEquals(listOf(FallbackCommit.Attempt("c", "group")), saved)
    }

    @Test fun rollbackCannotOverwriteANewerSelectionOrSession() {
        val state = FallbackCommit()
        assertFalse(state.shouldRestore("b", "group", sameClient = true, sameSession = true))
        state.switched("b", "group")
        assertTrue(state.shouldRestore("b", "group", sameClient = true, sameSession = true))
        assertFalse(state.shouldRestore("a", "group", sameClient = true, sameSession = true))
        assertFalse(state.shouldRestore("b", "other", sameClient = true, sameSession = true))
        assertFalse(state.shouldRestore("b", "group", sameClient = false, sameSession = true))
        assertFalse(state.shouldRestore("b", "group", sameClient = true, sameSession = false))
        state.confirmed(true, "b", "group", sameClient = true, sameRoute = true, sameSession = true)
        assertFalse(state.shouldRestore("b", "group", sameClient = true, sameSession = true))
    }

    @Test fun aSessionWithoutAGroupCanConfirmAnExactEntry() {
        val state = FallbackCommit()
        state.switched("entry", null)
        assertEquals(FallbackCommit.Attempt("entry", null),
            state.confirmed(true, "entry", null, sameClient = true, sameRoute = true, sameSession = true))
        assertNull(state.confirmed(true, "entry", null, sameClient = true, sameRoute = true, sameSession = true))
    }
}
