package com.vigil.android.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DateRangeFilterRow(
    fromDate: String,
    toDate: String,
    onApply: (String, String) -> Unit
) {
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    var tempFrom by remember(fromDate) { mutableStateOf(fromDate) }
    var tempTo by remember(toDate) { mutableStateOf(toDate) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = { showFromPicker = true },
            label = { Text("From: $tempFrom") },
            modifier = Modifier.weight(1f)
        )
        AssistChip(
            onClick = { showToPicker = true },
            label = { Text("To: $tempTo") },
            modifier = Modifier.weight(1f)
        )
    }


    if (showFromPicker) {
        DatePickerDialogSimple(
            initialDate = tempFrom,
            minDate = null,
            maxDate = minOf(tempTo, todayString()), // can't be after "to" or today
            onDismiss = { showFromPicker = false },
            onDateSelected = { picked ->
                tempFrom = picked
                showFromPicker = false
                onApply(tempFrom, tempTo)
            }
        )
    }

    if (showToPicker) {
        DatePickerDialogSimple(
            initialDate = tempTo,
            minDate = tempFrom,
            maxDate = todayString(),
            onDismiss = { showToPicker = false },
            onDateSelected = { picked ->
                tempTo = picked
                showToPicker = false
                onApply(tempFrom, tempTo)   // fire API immediately
            }
        )
    }
}

// ── internal helpers ──────────────────────────────────────────────────────────

private fun todayString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogSimple(
    initialDate: String,
    minDate: String?,
    maxDate: String?,
    onDismiss: () -> Unit,
    onDateSelected: (String) -> Unit
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    val initialMillis = remember(initialDate) {
        try { fmt.parse(initialDate)?.time } catch (e: Exception) { null }
    }

    val minMillis = remember(minDate) {
        try {
            if (minDate != null) {
                fmt.parse(minDate)?.time
            } else null
        } catch (e: Exception) { null }
    }

    val maxMillis = remember(maxDate) {
        try {
            if (maxDate != null) {
                // set to end-of-day so the max date itself is selectable
                val cal = Calendar.getInstance()
                cal.time = fmt.parse(maxDate) ?: Date()
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.timeInMillis
            } else null
        } catch (e: Exception) { null }
    }

    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val afterMin = minMillis == null || utcTimeMillis >= minMillis
                val beforeMax = maxMillis == null || utcTimeMillis <= maxMillis
                return afterMin && beforeMax
            }
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDateSelected(fmt.format(Date(millis)))
                    }
                },
                enabled = state.selectedDateMillis != null
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = state)
    }
}