package com.yingti.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test fun notConfiguredStartsAtAccount() {
        assertEquals(OnboardingStep.ACCOUNT, Onboarding.current(false, false, false))
        assertEquals(OnboardingStep.ACCOUNT, Onboarding.current(false, true, true))
    }

    @Test fun configuredWithoutToyOrAiIsToy() {
        assertEquals(OnboardingStep.TOY, Onboarding.current(true, false, false))
    }

    @Test fun toyConnectedMovesToAi() {
        assertEquals(OnboardingStep.AI, Onboarding.current(true, true, false))
    }

    @Test fun aiDoneFinishesEvenIfToyOffline() {
        assertEquals(OnboardingStep.DONE, Onboarding.current(true, false, true))
        assertEquals(OnboardingStep.DONE, Onboarding.current(true, true, true))
    }

    @Test fun visibility() {
        assertTrue(Onboarding.visible(OnboardingStep.TOY, dismissed = false))
        assertFalse(Onboarding.visible(OnboardingStep.TOY, dismissed = true))
        assertFalse(Onboarding.visible(OnboardingStep.DONE, dismissed = false))
    }

    @Test fun replayForcesVisibilityEvenWhenDoneAndDismissed() {
        assertTrue(Onboarding.visible(OnboardingStep.DONE, dismissed = true, replaying = true))
        assertFalse(Onboarding.visible(OnboardingStep.DONE, dismissed = true, replaying = false))
    }

    @Test fun nextReplayStepsThroughAllCards() {
        assertEquals(1, Onboarding.nextReplay(0))
        assertEquals(2, Onboarding.nextReplay(1))
        assertNull(Onboarding.nextReplay(2))
    }
}
