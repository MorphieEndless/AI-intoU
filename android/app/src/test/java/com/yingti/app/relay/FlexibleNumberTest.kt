package com.yingti.app.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FlexibleNumberTest {
    @Test fun acceptsJsonNumbers() {
        assertEquals(0.85, FlexibleNumber.parse(0.85, "intensity"), 0.0)
        assertEquals(3.0, FlexibleNumber.parse(3, "duration"), 0.0)
    }

    @Test fun acceptsNumericStringsFromModels() {
        assertEquals(0.85, FlexibleNumber.parse("0.85", "intensity"), 0.0)
        assertEquals(1.0, FlexibleNumber.parse(" 1 ", "intensity"), 0.0)
    }

    @Test fun acceptsPercentStrings() {
        assertEquals(0.85, FlexibleNumber.parse("85%", "intensity"), 0.0000001)
    }

    @Test fun rejectsInvalidOrNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            FlexibleNumber.parse("strong", "intensity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlexibleNumber.parse("NaN", "intensity")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlexibleNumber.parse(null, "intensity")
        }
    }
}
