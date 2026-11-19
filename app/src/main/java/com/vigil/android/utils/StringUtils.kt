package com.vigil.android.utils

import java.util.Locale

fun String.toTitleCase(): String {
    if (this.isBlank()) return this
    return this.split(" ").joinToString(" ") { word ->
        word.lowercase(Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
