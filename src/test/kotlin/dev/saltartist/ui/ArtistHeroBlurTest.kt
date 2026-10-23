package dev.saltartist.ui

import kotlin.test.*

class ArtistHeroBlurTest {
    @Test fun beginsClearIncreasesContinuouslyAndCanBeDisabled() {
        assertEquals(0f, heroBlurProgress(0f, true))
        val samples = (0..100).map { heroBlurProgress(it / 100f, true) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a && b - a < 0.04f })
        assertEquals(1f, samples.last())
        assertEquals(0f, heroBlurProgress(0.9f, false))
    }
    @Test fun adjacentBakedImagesBlendWithoutStepsAndMissingBlurStaysUsable() {
        assertEquals(HeroBlurBlend(2, 3, 0.5f), heroBlurBlend(0.5f, 6))
        assertEquals(HeroBlurBlend(5, 5, 0f), heroBlurBlend(1f, 6))
        assertEquals(HeroBlurBlend(0, 0, 0f), heroBlurBlend(0.7f, 1))
    }
}
