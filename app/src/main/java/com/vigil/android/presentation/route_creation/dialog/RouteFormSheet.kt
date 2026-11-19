package com.vigil.android.presentation.route_creation.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigil.android.presentation.route_creation.model.RouteFormState
import com.vigil.android.presentation.route_creation.model.Stop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteFormSheet(
    form: RouteFormState,
    isSaveEnabled: Boolean = true,
    onName: (String) -> Unit,
    onThana: (String) -> Unit,
    onAddStop: () -> Unit,
    onDeleteStop: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stopToDelete by remember { mutableStateOf<Stop?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (form.isEdit) "Edit Route" else "Add Route",
                    Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
                IconButton(onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            Column(Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())) {
                SectionTitle(Icons.Default.Info, "Route Details")
                OutlinedTextField(
                    value = form.routeName, onValueChange = onName,
                    label = { Text("Route Name *") }, placeholder = { Text("Enter route name") },
                    isError = form.routeNameError != null,
                    supportingText = form.routeNameError?.let { { Text(it) } },
                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.thana, onValueChange = onThana,
                    label = { Text("Thana Name *") }, placeholder = { Text("Enter thana name") },
                    isError = form.thanaError != null,
                    supportingText = form.thanaError?.let { { Text(it) } },
                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                SectionTitle(Icons.Default.Route, "Stops")

                if (form.stops.isEmpty()) {
                    EmptyStops(form.stopsError)
                } else {
                    StopsTable(form.stops)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onAddStop, modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add Stop", fontSize = 16.sp)
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onDismiss, Modifier
                    .weight(1f)
                    .height(52.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSave,
                    enabled = isSaveEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (form.isEdit) "Update Route" else "Add Route")
                }
            }
        }
    }

    // Delete confirmation
    stopToDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { stopToDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete stop?") },
            text = { Text("\"${s.name}\" will be removed from this route.") },
            confirmButton = {
                TextButton({ onDeleteStop(s.id); stopToDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ stopToDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}



@Composable
private fun EmptyStops(error: String?) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = Color(0xFFF7F9FC),
        border = BorderStroke(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Place, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            Text("No stops added yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StopsTable(stops: List<Stop>) {
    val expandedStops = remember(stops) {
        stops.flatMap { stop ->
            List(stop.frequency) { index ->
                stop to (index + 1)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(10.dp),
            ) {
                HeaderCell("#", 0.10f)
                HeaderCell("Stop Name", 0.38f)
                HeaderCell("Lat/Lng", 0.32f)
                HeaderCell("Freq/Time", 0.20f)
            }
            expandedStops.forEachIndexed { i, (s, visitIndex) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BodyCell("${i + 1}", 0.10f)
                    BodyCell("${s.name} (${visitIndex}/${s.frequency})", 0.38f)
                    Column(Modifier.weight(0.32f)) {
                        Text("%.4f".format(s.latitude), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("%.4f".format(s.longitude), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BodyCell("${s.frequencyTime}m", 0.20f)
                }
                if (i < expandedStops.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable private fun RowScope.HeaderCell(t: String, w: Float) =
    Text(t, Modifier.weight(w), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

@Composable private fun RowScope.BodyCell(t: String, w: Float) =
    Text(t, Modifier.weight(w), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)