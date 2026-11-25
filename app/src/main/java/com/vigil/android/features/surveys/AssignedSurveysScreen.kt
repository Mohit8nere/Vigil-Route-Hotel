package com.vigil.android.features.surveys

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assigned surveys", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { refresh++ }, enabled = !busy) {
                        Text("Refresh", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
            when {
                busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error)
                result.surveys.isEmpty() -> Text("No surveys assigned.", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(result.surveys, key = { it.id }) { row ->
                        val cardBackgroundBrush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFEAF5F0), // lightest greeny tint top
                                Color(0xFFF7FBF9)  // soft light green bottom
                            )
                        )
                        Card(
                            onClick = { selected = row.id; notice = "" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardBackgroundBrush, RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.title.toTitleCase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(row.location.toTitleCase(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(row.status.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                }
                                FilledTonalButton(
                                    onClick = { selected = row.id; notice = "" },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Open")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
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
}

private fun String.toTitleCase(): String = this.split(" ")
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() } }

@OptIn(ExperimentalMaterial3Api::class)
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

    val formattedTitle = remember(survey.title) { survey.title.toTitleCase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formattedTitle, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { back() }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val cardBackgroundBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFEAF5F0), // lightest greeny tint top
                    Color(0xFFF7FBF9)  // soft light green bottom
                )
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackgroundBrush, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(survey.location.toTitleCase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${survey.latitude}, ${survey.longitude}  ·  ${survey.radius} m radius", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (survey.instructions.isNotBlank()) Text(survey.instructions, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (completed) {
                Text("Completed", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (survey.description.isNotBlank()) Text(survey.description, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            val buttonGradientBrush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF00897B), // Teal Primary
                    Color(0xFF004D40)  // Dark Teal
                )
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { if (it.length <= 5000) notes = it },
                label = { Text("Survey description") },
                minLines = 3,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) verify()
                    else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (confirmedAt == 0L) "Confirm arrival using GPS" else "Recheck location",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AnimatedVisibility(visible = confirmedAt != 0L) {
                Text("Location confirmed. Photos can now be attached.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val cameraEnabled = !busy && photos.size < 6 && confirmedAt != 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (cameraEnabled) buttonGradientBrush
                            else Brush.horizontalGradient(listOf(Color(0xFFCCCCCC), Color(0xFF999999)))
                        )
                ) {
                    CameraButton(
                        label = "Take photo",
                        directory = model.drafts,
                        enabled = cameraEnabled,
                        modifier = Modifier.fillMaxSize(),
                        onError = { error = it },
                        onTaken = { attach(listOf(Uri.fromFile(it)), it) }
                    )
                }

                val pickerEnabled = !busy && photos.size < 6 && confirmedAt != 0L
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    enabled = pickerEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, if (pickerEnabled) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (pickerEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (pickerEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Select photos",
                        fontWeight = FontWeight.SemiBold,
                        color = if (pickerEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }

            photos.forEachIndexed { index, path ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Photo ${index + 1}  ·  ${(File(path).length() + 1023) / 1024} KB", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(enabled = !busy, onClick = { photos = ArrayList(photos - path); File(path).delete() }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
            }

            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            val submitEnabled = confirmedAt != 0L && !busy && (notes.isNotBlank() || photos.isNotEmpty())
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (submitEnabled) buttonGradientBrush
                        else Brush.horizontalGradient(listOf(Color(0xFFCCCCCC), Color(0xFF999999)))
                    )
            ) {
                Button(
                    enabled = submitEnabled,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    onClick = {
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
                    }
                ) {
                    Text("Submit survey", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
