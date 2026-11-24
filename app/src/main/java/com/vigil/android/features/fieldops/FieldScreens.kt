package com.vigil.android.features.fieldops

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vigil.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Composable
private fun EntryField(label: String, value: String, enabled: Boolean, type: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            when (label) {
                "Guest name" -> change(FieldInputRules.guestName(newValue))
                "Mobile number" -> change(FieldInputRules.phone(newValue))
                "Number of guests" -> change(newValue.filter(Char::isDigit).take(3))
                else -> change(newValue)
            }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = MaterialTheme.shapes.medium
    )
}

@Composable
private fun Heading(title: String, action: String, enabled: Boolean, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

private fun message(error: Exception): String {
    if (error is CancellationException) throw error
    return error.localizedMessage ?: "Unable to contact server. Retry when connected."
}

@Composable
fun HotelEntryScreen(onLogout: () -> Unit, model: FieldOperations = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var day by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()) }
    var guest by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("1") }
    var documents by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var clientId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var notice by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var submissionConflict by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Preserve a submission that may already have reached the server. */ }
    var hotel by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var historyError by remember { mutableStateOf("") }
    var loadingHistory by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val maxDocs = (count.toIntOrNull()?.coerceAtLeast(0)?.toLong() ?: 0L) * 2
    val valid = guest.isNotBlank() && origin.isNotBlank() &&
        FieldInputRules.validPhone(mobile) && maxDocs > 0 && documents.size.toLong() <= maxDocs

    LaunchedEffect(day, refresh) {
        loadingHistory = true
        history = emptyList()
        historyError = ""
        try {
            if (hotel.isBlank()) hotel = model.hotelName()
            history = model.entries(day)
        } catch (e: Exception) { historyError = message(e) }
        finally { loadingHistory = false }
    }

    fun addPhotos(uris: List<Uri>, cameraFile: File? = null) {
        if (uris.isEmpty()) return
        if (uris.size.toLong() + documents.size > maxDocs) {
            cameraFile?.delete()
            notice = "At most two images per guest. Select fewer images or update the guest count."
            return
        }
        busy = true
        scope.launch {
            val staged = mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    var size = documents.sumOf { File(it).length() }
                    for (uri in uris) {
                        val image = preparePhoto(context, uri, model.drafts)
                        staged.add(image)
                        size += image.length()
                        require(size <= 24L * 1024 * 1024) { "Combined images must fit within 24 MiB." }
                    }
                }
                documents = ArrayList(documents + staged.map { it.absolutePath })
                notice = ""
            } catch (e: Exception) {
                staged.forEach { it.delete() }
                notice = message(e)
            } finally {
                cameraFile?.delete()
                busy = false
            }
        }
    }

    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        showSignOutDialog = false
                        documents.forEach { File(it).delete() }
                        onLogout()
                    },
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            },
            shape = MaterialTheme.shapes.large
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> addPhotos(uris) }
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp, start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading(hotel.ifBlank { "Hotel guest register" }, "Sign out", !busy) {
            showSignOutDialog = true
        } }
        item {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val selected = LocalDate.parse(day)
                    DatePickerDialog(context, { _, y, m, d -> day = LocalDate.of(y, m + 1, d).toString() },
                        selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Entry date: $day")
                    Image(
                        painter = painterResource(id = R.drawable.calender),
                        contentDescription = "Calendar",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        item { EntryField("Guest name", guest, !busy) { guest = it.take(150) } }
        item { EntryField("Coming from", origin, !busy) { origin = it.take(200) } }
        item { EntryField("Mobile number", mobile, !busy, KeyboardType.Phone) { mobile = it } }
        item { EntryField("Number of guests", count, !busy, KeyboardType.Number) { count = it.filter(Char::isDigit).take(3) } }
        item {
            Spacer(Modifier.height(2.dp))
            Text("Document images  ${documents.size} / $maxDocs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy && documents.size < maxDocs,
                    modifier = Modifier.weight(1f).height(48.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.add_image),
                        contentDescription = "Add images",
                        modifier = Modifier.size(20.dp).padding(end = 6.dp)
                    )
                    Text("Add images")
                }
                CameraButton(
                    "Take photo",
                    model.drafts,
                    !busy && documents.size < maxDocs,
                    modifier = Modifier.weight(1f).height(48.dp),
                    onError = { notice = it }
                ) { file ->
                    addPhotos(listOf(Uri.fromFile(file)), file)
                }
            }
        }
        items(documents, key = { it }) { path ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Document ${documents.indexOf(path) + 1}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { documents = ArrayList(documents - path); File(path).delete() }, enabled = !busy) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !busy && valid && !submissionConflict,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    busy = true
                    notice = ""
                    scope.launch {
                        try {
                            model.submitEntry(mapOf("client_id" to clientId, "entry_date" to day, "guest_name" to guest.trim(),
                                "coming_from" to origin.trim(), "mobile" to mobile, "guest_count" to count),
                                documents.map(::File))
                            documents.forEach { File(it).delete() }
                            documents = arrayListOf()
                            guest = ""; origin = ""; mobile = ""; count = "1"
                            clientId = UUID.randomUUID().toString()
                            notice = "Guest entry saved."
                            refresh++
                        } catch (e: SubmissionConflict) {
                            submissionConflict = true
                            notice = "A previous version of this entry was saved. Check the entries below before starting a separate entry."
                            refresh++
                        } catch (e: Exception) { notice = message(e) }
                        finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Please wait…" else "Save guest entry") }
        }
        if (submissionConflict) item {
            OutlinedButton(
                onClick = {
                    clientId = UUID.randomUUID().toString()
                    submissionConflict = false
                    notice = "Separate entry started. Review the details, then save."
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) { Text("Start a separate guest entry") }
        }
        if (notice.isNotBlank()) item {
            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Heading("Entries for $day", if (loadingHistory) "Loading…" else "Refresh", !busy && !loadingHistory) {
                refresh++
            }
        }
        when {
            loadingHistory -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            historyError.isNotBlank() -> item { Text(historyError, color = MaterialTheme.colorScheme.error) }
            history.isEmpty() -> item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Text("No entries for this date.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> items(history) { text ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ChallanScreen(onBack: () -> Unit, model: FieldOperations = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filePath by rememberSaveable { mutableStateOf<String?>(null) }
    var clientId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var historyError by remember { mutableStateOf("") }
    var loadingHistory by remember { mutableStateOf(false) }
    BackHandler(enabled = busy) { /* Preserve a submission that may already have reached the server. */ }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loadingHistory = true
        historyError = ""
        try { history = model.challans() } catch (e: Exception) { historyError = message(e) }
        finally { loadingHistory = false }
    }
    val preview by produceState<android.graphics.Bitmap?>(null, filePath) {
        value = null
        val path = filePath ?: return@produceState
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("Challan photo", "Back", !busy, onBack) }
        item { Text("Take a clear photo showing the vehicle number and the violation. The control room will review it.") }
        if (preview != null) item { Image(preview!!.asImageBitmap(), "Captured vehicle photograph",
            modifier = Modifier.fillMaxWidth().height(220.dp)) }
        item {
            CameraButton(
                label = if (filePath == null) "Open camera" else "Retake photo",
                directory = model.drafts,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onError = { notice = it }
            ) { source ->
                busy = true
                scope.launch {
                    try {
                        val photo = withContext(Dispatchers.IO) { prepareChallanPhoto(context, source, model.drafts) }
                        filePath?.let { File(it).delete() }
                        filePath = photo.absolutePath
                        clientId = UUID.randomUUID().toString()
                        notice = ""
                    } catch (e: Exception) { notice = message(e) }
                    finally {
                        if (filePath != source.absolutePath) source.delete()
                        busy = false
                    }
                }
            }
        }
        item {
            Button(
                enabled = filePath != null && !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    val file = filePath?.let(::File) ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            val id = model.submitChallan(clientId, file)
                            file.delete(); filePath = null
                            notice = "Photo #$id submitted for admin review."
                            refresh++
                        } catch (e: Exception) { notice = message(e) }
                        finally { busy = false }
                    }
                }
            ) { Text(if (busy) "Please wait…" else "Submit for review") }
        }
        if (notice.isNotBlank()) item { Text(notice, color = MaterialTheme.colorScheme.primary) }
        item { HorizontalDivider(); Heading("My submissions", "Refresh", !loadingHistory && !busy) { refresh++ } }
        when {
            loadingHistory -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            historyError.isNotBlank() -> item { Text(historyError, color = MaterialTheme.colorScheme.error) }
            history.isEmpty() -> item { Text("No challan photos submitted.") }
            else -> items(history) { text -> Text(text); HorizontalDivider() }
        }
    }
}
