package com.vigil.android.presentation.route_creation.dialog

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vigil.android.presentation.route_creation.location_helper.hasLocationPermission
import com.vigil.android.presentation.route_creation.model.AddStopState
import com.vigil.android.presentation.route_creation.model.LocationState


@Composable
fun AddStopDialog(
    state: AddStopState,
    onName: (String) -> Unit,
    onSubmit: (Int, Int) -> Unit,
    onPermissionDenied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val busy = state.location is LocationState.Fetching || state.location is LocationState.Success
    
    var frequency by remember { mutableStateOf("") }
    var frequencyTime by remember { mutableStateOf("") }
    
    val isFormValid = state.stopName.isNotBlank() && 
                      frequency.toIntOrNull()?.let { it in 1..7 } == true &&
                      frequencyTime.toIntOrNull()?.let { it > 0 } == true

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> if (result.values.any { it }) onSubmit(frequency.toIntOrNull() ?: 1, frequencyTime.toIntOrNull() ?: 0) else onPermissionDenied() }

    fun startCapture() {
        if (context.hasLocationPermission()) onSubmit(frequency.toIntOrNull() ?: 1, frequencyTime.toIntOrNull() ?: 0)
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Stop", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "Close") }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.stopName, onValueChange = onName,
                    label = { Text("Stop Name *") }, placeholder = { Text("e.g. Main Gate") },
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { { Text(it) } },
                    enabled = !busy, singleLine = true,
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
                )
                
                Spacer(Modifier.height(16.dp))
                
                // Frequency Section UI
                Text("Frequency *", fontWeight = FontWeight.Bold)
                Text("Select how often this stop needs to be visited", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { input ->
                            frequency = input.filter { it.isDigit() }
                        },
                        label = { Text("Frequency *") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = frequencyTime,
                        onValueChange = { input ->
                            frequencyTime = input.filter { it.isDigit() }
                        },
                        label = { Text("Time (min) *") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(16.dp))

                LocationStatusCard(state.location, onRetry = ::startCapture)

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = ::startCapture, enabled = !busy && isFormValid,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(8.dp))
                    Text("Add Stop", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun LocationStatusCard(loc: LocationState, onRetry: () -> Unit) {
    val ok = Color(0xFF2E7D32)
    when (loc) {
        LocationState.Idle -> InfoBox(Icons.Default.MyLocation, MaterialTheme.colorScheme.primary,
            "Current location will be added automatically with this stop.")

        LocationState.Fetching -> Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("Fetching current location...", fontWeight = FontWeight.Medium)
            }
        }

        is LocationState.Success -> Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFE8F5E9)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = ok)
                    Spacer(Modifier.width(8.dp))
                    Text("Location captured successfully", color = ok, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text("Latitude: ${"%.4f".format(loc.lat)}")
                Text("Longitude: ${"%.4f".format(loc.lng)}")
            }
        }

        LocationState.PermissionDenied -> ErrorBox("Location permission is required to add a stop.", onRetry)
        is LocationState.Error -> ErrorBox(loc.message, onRetry)
    }
}

@Composable
private fun InfoBox(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint); Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOff, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp)
            }
            TextButton(onRetry, Modifier.align(Alignment.End)) { Text("Retry") }
        }
    }
}