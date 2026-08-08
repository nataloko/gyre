package dev.gyre.wallpaper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun invalidStoredValuesFallBackToValidatedDefaults() {
        assertEquals(1f, SettingsValidation.bounded(Float.NaN, 1f, 0.25f..2f), 0f)
        assertEquals(2f, SettingsValidation.bounded(8f, 1f, 0.25f..2f), 0f)
        assertEquals(
            setOf("valid"),
            SettingsValidation.validIds(setOf("valid", "missing"), setOf("valid")),
        )
    }

    /**
     * The hours interval has no NaN to reject, so absence and range are all there is to check —
     * and 0 has to survive both, being the value that turns the timed change off.
     */
    @Test
    fun anAbsentOrOutOfRangeIntervalIsBroughtToRange() {
        assertEquals(0, SettingsValidation.bounded(null, 0, 0..MAX_RANDOM_CHANGE_HOURS))
        assertEquals(0, SettingsValidation.bounded(-3, 0, 0..MAX_RANDOM_CHANGE_HOURS))
        assertEquals(
            MAX_RANDOM_CHANGE_HOURS,
            SettingsValidation.bounded(999, 0, 0..MAX_RANDOM_CHANGE_HOURS),
        )
        assertEquals(6, SettingsValidation.bounded(6, 0, 0..MAX_RANDOM_CHANGE_HOURS))
    }
}
