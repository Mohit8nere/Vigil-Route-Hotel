package com.vigil.android.features.fieldops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldInputRulesTest {
    @Test fun keepsHindiVowelMarksAndConjuncts() {
        assertEquals("मोहन श्रीवास्तव", FieldInputRules.guestName("मोहन श्रीवास्तव"))
    }

    @Test fun keepsApostrophesAndCombiningAccents() {
        val name = "Jose\u0301 O’Connor"
        assertEquals(name, FieldInputRules.guestName(name))
    }

    @Test fun acceptsCountryCodeWithoutTruncatingToTenDigits() {
        val value = FieldInputRules.phone("+91 98765 43210")
        assertEquals("+919876543210", value)
        assertTrue(FieldInputRules.validPhone(value))
    }

    @Test fun rejectsMalformedAndShortNumbers() {
        assertFalse(FieldInputRules.validPhone(FieldInputRules.phone("91+9876543210")))
        assertFalse(FieldInputRules.validPhone("123456"))
        assertTrue(FieldInputRules.validPhone("9876543210"))
    }
}
