package io.github.nanomuse.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRewardsTest {
    private fun initial(xp: Long = 0L, earned: Int = 0) = PetRewardState(xp, PetDaily(day = 1, earned = earned))

    @Test fun definitionsMatchTheNineMissions() {
        assertEquals(9, PetMission.entries.size)
        assertEquals(120, PetMission.entries.sumOf { it.xp })
        assertEquals(100, PetRewards.DAILY_CAP)
        assertEquals(3, PetMission.CONVERSATION.target)
        assertEquals(2, PetMission.LEARN.target)
        assertEquals(30, PetMission.GOAL.xp)
    }

    @Test fun cycleAndMissionShareTheAllowance() {
        var state = PetRewards.cycle(initial(), PetTurnOutcome.SUCCESS)
        assertEquals(25L, state.xp) // 10 for the cycle, 15 for the first completed task
        state = PetRewards.cycle(state, PetTurnOutcome.FAILURE)
        assertEquals(28L, state.xp)
        assertEquals(state, PetRewards.cycle(state, PetTurnOutcome.CANCELLED))
        assertEquals(28, state.daily.earned)
        assertTrue(state.daily.completed(PetMission.TASK))
    }

    @Test fun conversationAndLearnNeedTheirTargetsAndNeverPayTwice() {
        var state = PetRewards.event(initial(), PetEvent.CHAT)
        assertEquals(5L, state.xp)
        state = PetRewards.event(state, PetEvent.CHAT)
        assertFalse(state.daily.completed(PetMission.CONVERSATION))
        state = PetRewards.event(state, PetEvent.CHAT)
        assertEquals(11L, state.xp)
        state = PetRewards.event(state, PetEvent.CHAT)
        assertEquals(11L, state.xp)
        state = PetRewards.event(state, PetEvent.LEARN)
        assertEquals(11L, state.xp)
        state = PetRewards.event(state, PetEvent.LEARN)
        assertEquals(27L, state.xp)
        assertEquals(27L, PetRewards.event(state, PetEvent.LEARN).xp)
    }

    @Test fun goalRewardIsNotSubjectToTheOldSingleTurnCap() {
        val state = PetRewards.event(initial(), PetEvent.GOAL)
        assertEquals(30L, state.xp)
        assertEquals(30, state.daily.earned)
    }

    @Test fun finalRewardIsPartialAndCompletedEvenAtTheCap() {
        val state = PetRewards.event(initial(xp = 600, earned = 98), PetEvent.GOAL)
        assertEquals(602L, state.xp)
        assertEquals(100, state.daily.earned)
        assertTrue(state.daily.completed(PetMission.GOAL))
        val noRoom = PetRewards.event(state, PetEvent.SKILL)
        assertEquals(602L, noRoom.xp)
        assertTrue(noRoom.daily.completed(PetMission.SKILL))
        assertEquals(noRoom, PetRewards.event(noRoom, PetEvent.SKILL))
    }

    @Test fun stageChangeIsOneMilestoneInsideTheSameCap() {
        val start = PetLadder.levelStart(5) - PetXpRules.SUCCESS
        val result = PetRewards.cycle(initial(xp = start), PetTurnOutcome.SUCCESS)
        assertEquals(start + 10 + 25 + 15, result.xp)
        assertEquals(50, result.daily.earned)
        assertTrue(result.daily.completed(PetMission.MILESTONE))
        assertEquals(PetStage.DISCOVERY, PetLadder.progressFor(result.xp).stage)
    }

    @Test fun dayMovesForwardButNeverBackwards() {
        val day = PetDaily(day = 10, earned = 100, missions = mapOf(PetMission.GOAL to 1))
        assertEquals(day, PetRewards.onDay(day, 9))
        assertEquals(day, PetRewards.onDay(day, 10))
        assertEquals(PetDaily(day = 11), PetRewards.onDay(day, 11))
    }

    @Test fun totalAndHashedIdentifiersAreBounded() {
        val result = PetRewards.event(initial(xp = Long.MAX_VALUE - 3), PetEvent.GOAL)
        assertEquals(Long.MAX_VALUE, result.xp)
        assertEquals(3, result.daily.earned)
        val first = PetRewards.eventKey("CHAT", "private message identity")
        assertEquals(64, first.length)
        assertTrue(PetTurnLedger.validId(first))
        assertFalse(first.contains("private"))
        assertFalse(first == PetRewards.eventKey("GOAL", "private message identity"))
    }
}
