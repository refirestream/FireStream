package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.settings.extensions.FireScore
import org.junit.Assert.assertEquals
import org.junit.Test

class FireScoreTest {
    @Test
    fun tiersFollowScoreCutoffs() {
        // 0–50 Cold, 51–79 Hot, 80+ Blue Fire; half-point boundaries align the
        // tier with the rounded percentage the badge shows.
        assertEquals(FireScore.NEW, FireScore.of(null))
        assertEquals(FireScore.COLD, FireScore.of(0.0))
        assertEquals(FireScore.COLD, FireScore.of(50.0))
        assertEquals(FireScore.HOT, FireScore.of(51.0))
        assertEquals(FireScore.HOT, FireScore.of(79.0))
        assertEquals(FireScore.BLUE_FIRE, FireScore.of(80.0))
        assertEquals(FireScore.BLUE_FIRE, FireScore.of(100.0))
    }
}
