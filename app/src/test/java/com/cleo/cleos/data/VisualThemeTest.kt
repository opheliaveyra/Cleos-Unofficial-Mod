package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualThemeTest {
    @Test fun missingOrUnknownNeverOptsIntoScrapbook() {
        listOf(null, "", "glass", "paper", "crystal", "lavender_scrapbook ", "broken").forEach {
            assertEquals(VisualTheme.NATIVE, VisualTheme.normalize(it))
        }
    }

    @Test fun explicitChoiceSurvivesNormalizationAndNativeCanBeRestored() {
        assertEquals(VisualTheme.LAVENDER, VisualTheme.normalize(VisualTheme.normalize(VisualTheme.LAVENDER)))
        assertEquals(VisualTheme.NATIVE, VisualTheme.normalize(VisualTheme.NATIVE))
    }
}
