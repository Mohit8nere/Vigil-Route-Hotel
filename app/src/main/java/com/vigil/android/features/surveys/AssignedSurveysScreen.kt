package com.vigil.android.features.surveys

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vigil.android.features.fieldops.CameraButton
import com.vigil.android.features.fieldops.FieldOperations
import com.vigil.android.features.fieldops.preparePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun errorText(e: Exception): String {
    if (e is CancellationException) throw e
    return e.localizedMessage ?: "Could not complete the request. Please retry."
}

@Composable
fun AssignedSurveysScreen(onBack: (() -> Unit)? = null, model: FieldOperations = hiltViewModel()) {
    var page by rememberSaveable { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf(SurveyPage(emptyList(), false)) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(page, refresh) {
        busy = true
        error = ""
        try { result = model.surveys(page) }
        catch (e: Exception) { error = errorText(e) }
        finally { busy = false }
    }
    val survey = result.surveys.firstOrNull { it.id == selected }
    if (survey != null) {
        key(survey.id) {
            SurveyVisit(survey, model, { selected = null }, {
                notice = "Survey submitted."
                selected = null
                refresh++
            })
        }
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
            Text("Assigned surveys", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = { refresh++ }, enabled = !busy) { Text("Refresh") }
        }
        if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
        when {
            busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
            result.surveys.isEmpty() -> Text("No surveys assigned.", Modifier.padding(vertical = 20.dp))
            else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                items(result.surveys, key = { it.id }) { row ->
                    Card(onClick = { selected = row.id; notice = "" }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium)
                            Text(row.location)
                            Text(row.status.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
        if (!busy && (page > 1 || result.hasNext)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { page-- }, enabled = page > 1) { Text("Previous") }
                Text("Page $page", Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = { page++ }, enabled = result.hasNext) { Text("Next") }
            }
        }
    }
}

@Composable
private fun SurveyVisit(survey: AssignedSurvey, model: FieldOperations, onBack: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notes by rememberSaveable { mutableStateOf(survey.description) }
    var photos by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var confirmedAt by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val completed = survey.status == "completed"

    LaunchedEffect(confirmedAt) {
        if (confirmedAt != 0L) {
            delay(90000)
            confirmedAt = 0
        }
    }
    fun verify() {
        if (busy) return
        scope.launch {
            busy = true; error = ""; confirmedAt = 0
            try {
                model.arrive(survey, surveyFix(context))
                confirmedAt = SystemClock.elapsedRealtime()
            } catch (e: Exception) { error = errorText(e) }
            finally { busy = false }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) verify()
        else error = "Enable precise location to confirm arrival."
    }
    fun attach(uris: List<Uri>, capture: File? = null) {
        if (uris.isEmpty()) return
        if (confirmedAt == 0L || photos.size + uris.size > 6) {
            capture?.delete()
            error = if (confirmedAt == 0L) "Recheck your location to attach photos." else "A survey accepts up to six photos."
            return
        }
        busy = true; error = ""
        scope.launch {
            val staged = mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris) staged.add(preparePhoto(context, uri, model.drafts, compact = true))
                }
                photos = ArrayList(photos + staged.map { it.absolutePath })
                staged.clear()
            } catch (e: Exception) { error = errorText(e) }
            finally { staged.forEach { it.delete() }; capture?.delete(); busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { attach(it) }
    fun back() {
        if (busy) return
        photos.forEach { File(it).delete() }
        onBack()
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { back() }, enabled = !busy) { Text("Back to surveys") }
        Text(survey.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(survey.location, style = MaterialTheme.typography.titleMedium)
                Text("${survey.latitude}, ${survey.longitude}  ·  ${survey.radius} m radius")
                if (survey.instructions.isNotBlank()) Text(survey.instructions)
            }
        }
        if (completed) {
            Text("Completed", color = MaterialTheme.colorScheme.primary)
            if (survey.description.isNotBlank()) Text(survey.description)
            return@Column
        }
        OutlinedTextField(value = notes, onValueChange = { if (it.length <= 5000) notes = it },
            label = { Text("Survey description") }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) verify()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, enabled = !busy) { Text(if (confirmedAt == 0L) "Confirm arrival using GPS" else "Recheck location") }
        AnimatedVisibility(visible = confirmedAt != 0L) {
            Text("Location confirmed. Photos can now be attached.", color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraButton("Take photo", model.drafts, !busy && photos.size < 6 && confirmedAt != 0L,
                onError = { error = it }, onTaken = { attach(listOf(Uri.fromFile(it)), it) })
            OutlinedButton(onClick = { picker.launch("image/*") },
                enabled = !busy && photos.size < 6 && confirmedAt != 0L) { Text("Select photos") }
        }
        photos.forEachIndexed { index, path ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Photo ${index + 1}  ·  ${(File(path).length() + 1023) / 1024} KB", Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { photos = ArrayList(photos - path); File(path).delete() }) { Text("Remove") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = confirmedAt != 0L && !busy && (notes.isNotBlank() || photos.isNotEmpty()),
            modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    busy = true; error = ""
                    try {
                        model.submit(survey, surveyFix(context), notes.trim(), photos.map(::File))
                        photos.forEach { File(it).delete() }
                        photos = arrayListOf()
                        onComplete()
                    } catch (e: Exception) { error = errorText(e); confirmedAt = 0 }
                    finally { busy = false }
                }
            }) { Text("Submit survey") }
    }
}
