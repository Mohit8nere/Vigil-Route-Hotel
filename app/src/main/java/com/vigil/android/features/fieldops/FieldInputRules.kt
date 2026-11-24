package com.vigil.android.features.fieldops

/** Input rules shared with the guest-entry API, without discarding parts of people's names. */
internal object FieldInputRules {
    fun guestName(value: String): String = value.take(150)

    // The API accepts an optional leading + and 7-15 ASCII digits. Keep malformed +
    // positions visible for validation rather than silently changing a phone number.
    fun phone(value: String): String = value.filter { it in '0'..'9' || it == '+' }.take(16)
    fun validPhone(value: String): Boolean = Regex("\\+?[0-9]{7,15}").matches(value)
}
