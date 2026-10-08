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
}
