package com.vigil.android.utils

import java.util.Locale

fun String.toTitleCase(): String {
    if (this.isBlank()) return this
    return this.trim().split(Regex("\\s+")).joinToString(" ") { word ->
        if (word.length <= 3 && word.all { it.isUpperCase() }) {
            word
        } else {
            word.lowercase(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }
}
