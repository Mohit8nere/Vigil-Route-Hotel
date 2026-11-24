package com.vigil.android.presentation.verification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import com.vigil.android.presentation.components.DateRangeFilterRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.vigil.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
private suspend fun verificationFix(context: Context): Location =
    suspendCancellableCoroutine { result ->
        val token = CancellationTokenSource()
        result.invokeOnCancellation { token.cancel() }
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0).setDurationMillis(30_000L).build()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(request, token.token)
            .addOnSuccessListener { fix ->
                if (!result.isActive) return@addOnSuccessListener
                if (fix == null) {
                    result.resumeWithException(IllegalStateException("Current location unavailable. Move to an open area and retry."))
                } else {
                    val problem = verificationLocationProblem(
                        fix.latitude, fix.longitude, fix.accuracy.takeIf { fix.hasAccuracy() },
                        SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos,
                    )
                    if (problem != null) result.resumeWithException(IllegalStateException(problem))
                    else result.resume(fix)
                }
            }.addOnFailureListener { if (result.isActive) result.resumeWithException(it) }
    }

private suspend fun capture(controller: LifecycleCameraController): Bitmap =
    suspendCancellableCoroutine { result ->
        val executor = Executors.newSingleThreadExecutor()
        try {
            controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val original = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees
                        val ratio = minOf(1f, 1280f / maxOf(original.width, original.height))
                        val transform = Matrix().apply {
                            postScale(
                                ratio,
                                ratio
                            ); postRotate(degrees.toFloat())
                        }
                        val upright = Bitmap.createBitmap(
                            original,
                            0,
                            0,
                            original.width,
                            original.height,
                            transform,
                            true
                        )
                        if (upright !== original) original.recycle()
                        result.resume(upright) { _, bitmap, _ -> bitmap.recycle() }
                    } catch (e: Exception) {
                        if (result.isActive) result.resumeWithException(e)
                    } finally {
                        image.close(); executor.shutdown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    executor.shutdown()
                    if (result.isActive) result.resumeWithException(exception)
                }
            })
        } catch (e: Exception) {
            executor.shutdown()
            if (result.isActive) result.resumeWithException(e)
        }
    }

@Composable
fun VerificationScreen() {
    val context = LocalContext.current
    val host = LocalView.current
    val lifecycle = checkNotNull(host.findViewTreeLifecycleOwner())
    val scope = rememberCoroutineScope()
    val store = remember { VerificationStore(context) }
    DisposableEffect(store) {
        onDispose {
            // Store close waits for synchronous work already in flight; never block the UI.
            CoroutineScope(Dispatchers.IO).launch { store.close() }
        }
    }
    var summary by remember { mutableStateOf(LocalSummary(-1, 0, 0, 0, "")) }
    var usableAssignments by remember(summary.version) { mutableStateOf(emptySet<Long>()) }
    var status by remember { mutableStateOf(JSONObject()) }
    var failures by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var target by remember { mutableStateOf<Long?>(null) }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var scanGeneration by remember { mutableStateOf(0L) }
    var cameraPermissionRequest by remember { mutableStateOf<VerificationScanContext?>(null) }
    var locationPermissionPending by remember { mutableStateOf(false) }
    var permissionHelp by remember { mutableStateOf(false) }
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }

    fun job(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Please retry."
            } finally {
                busy = false
            }
        }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var endDate by remember { mutableStateOf(dateFormat.format(Date())) }
    var startDate by remember {
        mutableStateOf(
            run {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -30)
                dateFormat.format(cal.time)
            }
        )
    }
    var officerHistory by remember { mutableStateOf(JSONObject()) }
    var historyLoading by remember { mutableStateOf(false) }

    fun loadHistory(from: String = startDate, to: String = endDate) {
        job {
            historyLoading = true
            try {
                val officerId = withContext(Dispatchers.IO) { store.getOfficerId() }
                val res = withContext(Dispatchers.IO) { store.getOfficerHistory(officerId, from, to) }
                officerHistory = res
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Failed to load verification history."
            } finally {
                historyLoading = false
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            loadHistory()
        }
    }

    fun referencesReady() = verificationReferencesReady(
        summary.version, summary.count,
        if (status.has("version")) status.optLong("version") else null,
    ) && (target == null || target in usableAssignments)

    fun scanContext() = VerificationScanContext(scanGeneration, selectedTab, target, summary.version)

    fun clearScan() {
        scanGeneration += 1
        candidate = null
        camera = false
    }

    suspend fun refreshLocal() {
        val local = withContext(Dispatchers.IO) {
            Triple(
                store.summary(),
                store.state("status"),
                store.failedEvents()
            )
        }
        summary = local.first
        status = local.second
        failures = local.third
    }

    suspend fun updateStatus() {
        withContext(Dispatchers.IO) {
            store.flush()
            VerificationJobs.publish(context, store, store.refreshStatus())
        }
    }

    val performSync: () -> Unit = {
        job {
            clearScan(); message = "Checking for reference updates…"
            withContext(Dispatchers.IO) {
                store.sync { done, total ->
                    scope.launch {
                        message = "Preparing references $done / $total"
                    }
                }
            }
            refreshLocal()
            message = "Reference database updated."
            updateStatus(); refreshLocal()
        }
    }

    val performRefresh: () -> Unit = {
        job {
            updateStatus(); refreshLocal(); message =
            "Assignments and upload status refreshed."
        }
    }

    LaunchedEffect(Unit) {
        job {
            VerificationJobs.schedule(context)
            refreshLocal()
            try {
                updateStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Using the saved reference database. ${e.localizedMessage.orEmpty()}"
            }
            refreshLocal()
        }
    }

    val notifyPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) job { updateStatus(); refreshLocal() }
            else message = "Assignments and database updates remain visible on this screen."
        }
    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requested = cameraPermissionRequest
            cameraPermissionRequest = null
            if (requested?.isCurrent(scanContext(), referencesReady()) == true) {
                camera = granted
                permissionHelp = !granted
                if (!granted) message = "Camera permission is needed for a live verification."
            }
        }
    val locationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            locationPermissionPending = false
            val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            permissionHelp = !precise
            message =
                if (precise) "Precise location is available. Confirm after reviewing the candidate."
                else "Enable precise location in app permissions to record the verification site."
        }
    fun openCamera() {
        if (busy || cameraPermissionRequest != null || locationPermissionPending) return
        if (!referencesReady()) {
            message = if (summary.version < 0 || summary.count == 0)
                "Sync a usable reference database before opening the camera."
            else if (target != null && target !in usableAssignments)
                "This record needs usable reference photos. Sync or choose another record."
            else "Sync the updated reference database before scanning."
            return
        }
        candidate = null
        message = ""
        permissionHelp = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera = true
        } else {
            val requested = scanContext()
            cameraPermissionRequest = requested
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val canCapture = referencesReady()
    val interactionBlocked = busy || cameraPermissionRequest != null || locationPermissionPending
    LaunchedEffect(canCapture) {
        if (!canCapture) clearScan()
    }
    val pending = status.optJSONArray("assignments") ?: JSONArray()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Face verification",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(enabled = !interactionBlocked, onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(id = R.drawable.menu_bg),
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sync reference database") },
                        enabled = !interactionBlocked,
                        onClick = {
                            menuExpanded = false
                            performSync()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh assignments and uploads") },
                        enabled = !interactionBlocked,
                        onClick = {
                            menuExpanded = false
                            performRefresh()
                        }
                    )
                }
            }
        }
        Text(
            if (summary.version < 0) "Sync once to enable offline matching."
            else "Saved database v${summary.version} · ${summary.count} usable profiles · ${summary.pending} awaiting upload",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status.has("version") && status.optLong("version") != summary.version) {
            Text(
                "An updated reference database is available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (summary.unusable.isNotBlank()) {
            Text(
                "Reference photos need correction for: ${summary.unusable}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            TextButton(
                enabled = !interactionBlocked,
                onClick = { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enable update alerts")
            }
        }

        // TABS: Verification Records, General Scan, Completed Records
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                enabled = !interactionBlocked,
                onClick = {
                    clearScan()
                    selectedTab = 0
                    message = ""
                },
                text = { Text("Verification Records") }
            )
            Tab(
                selected = selectedTab == 1,
                enabled = !interactionBlocked,
                onClick = {
                    clearScan()
                    selectedTab = 1
                    target = null
                    message = ""
                    openCamera()
                },
                text = { Text("General Scan") }
            )
            Tab(
                selected = selectedTab == 2,
                enabled = !interactionBlocked,
                onClick = {
                    clearScan()
                    selectedTab = 2
                    message = ""
                },
                text = { Text("Completed") }
            )
        }

        when (selectedTab) {
            0 -> {
                // Face Verification Records Tab
                Text(
                    "Select an assigned person to review their reference and scan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pending.length() == 0) {
                    Text(
                        "No pending assignments on this device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(pending.objects()) { assignment ->
                            val id = assignment.getLong("subject")
                            val isSelected = target == id

                            val profile by produceState<JSONObject?>(null, id, summary.version) {
                                value = null
                                value = withContext(Dispatchers.IO) {
                                    runCatching { store.getProfile(id) }.getOrNull()
                                }
                            }
                            val profileUsable = (profile?.optJSONArray("vectors")?.length() ?: 0) > 0
                            LaunchedEffect(id, summary.version, profileUsable) {
                                usableAssignments = if (profileUsable) usableAssignments + id else usableAssignments - id
                            }
                            val thumbBase64 = profile?.optString("thumbnail").orEmpty()
                            val bitmap by produceState<Bitmap?>(null, thumbBase64) {
                                value = null
                                value = if (thumbBase64.isNotBlank()) {
                                    withContext(Dispatchers.Default) {
                                        runCatching {
                                            val bytes = android.util.Base64.decode(thumbBase64, android.util.Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        }.getOrNull()
                                    }
                                } else null
                            }
                            val subjectName = profile?.optString("name")?.takeIf { it.isNotBlank() }
                                ?: assignment.optString("subject_name", "Subject")
                            val shortDescription = profile?.optString("description")?.takeIf { it.isNotBlank() }
                                ?: profile?.optString("category_name")?.takeIf { it.isNotBlank() }
                                ?: "Due: ${assignment.optString("due_date", "")}"
                            val thumbnailBitmap = bitmap

                            Card(
                                modifier = Modifier
                                    .width(160.dp)
                                    .clickable(enabled = !interactionBlocked && profileUsable) {
                                        clearScan()
                                        target = id
                                        usableAssignments = usableAssignments + id
                                        message = ""
                                        openCamera()
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = if (isSelected) BorderStroke(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary
                                ) else null,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                            .padding(6.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (thumbnailBitmap != null) {
                                            Image(
                                                thumbnailBitmap.asImageBitmap(),
                                                contentDescription = "Subject photo",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "👤",
                                                    style = MaterialTheme.typography.headlineMedium
                                                )
                                            }
                                        }
                                    }

                                    // Text content below image
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = subjectName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = shortDescription,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                        if (!profileUsable) {
                                            Text(
                                                text = if (profile == null) "Reference unavailable — sync first."
                                                    else "Reference photo needs replacement.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (target != null) {
                    TextButton(
                        enabled = !interactionBlocked,
                        onClick = {
                            scanGeneration += 1
                            target = null
                            candidate = null
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use the whole saved database") }
                }

            }

            1 -> {
                // General Scan Tab - Direct camera open for all database scan
                Text(
                    "Scan a live face against your synced references. Review each suggestion before confirming.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            2 -> {
                // Completed Records Tab
                Text(
                    "Confirmed verifications for the selected dates",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))

                DateRangeFilterRow(
                    fromDate = startDate,
                    toDate = endDate,
                    onApply = { from, to ->
                        startDate = from
                        endDate = to
                        loadHistory(from, to)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                val results = officerHistory.optJSONArray("results") ?: JSONArray()
                val officerName = officerHistory.optString("officer_name", "")
                val totalVerifications = officerHistory.optInt("total_verifications", results.length())

                if (officerName.isNotBlank()) {
                    Text(
                        text = "Officer: $officerName · Total Verifications: $totalVerifications",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (historyLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (results.length() == 0) {
                    val completedList = status.optJSONArray("completed")
                        ?: status.optJSONArray("history")
                        ?: status.optJSONArray("verified")
                        ?: status.optJSONArray("completed_assignments")
                        ?: JSONArray()

                    if (completedList.length() == 0) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "No completed records found for this date range.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (item in completedList.objects()) {
                                val subjectName = item.optString("subject_name", item.optString("name", "Completed Subject"))
                                val completedTime = item.optString("completed_at", item.optString("verified_at", item.optString("due_date", "")))

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = subjectName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (completedTime.isNotBlank()) {
                                                Text(
                                                    text = "Completed: $completedTime",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "✅ Completed",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (item in results.objects()) {
                            val verificationId = item.optInt("verification_id", 0)
                            val verifiedAt = item.optString("verified_at", "")
                            val suspect = item.optJSONObject("suspect")
                            val suspectName = suspect?.optString("name", "Subject") ?: "Subject"
                            val description = suspect?.optString("description", "") ?: ""
                            val address = suspect?.optString("address", "") ?: ""
                            val crimeCategory = suspect?.optString("crime_category", "") ?: ""

                            val location = item.optJSONObject("location")
                            val lat = location?.optDouble("latitude", 0.0) ?: 0.0
                            val lng = location?.optDouble("longitude", 0.0) ?: 0.0
                            val accuracy = location?.optDouble("gps_accuracy_m", 0.0) ?: 0.0

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = suspectName,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "✅ Completed",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (crimeCategory.isNotBlank()) {
                                        Text(
                                            text = "Crime Category: $crimeCategory",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    if (description.isNotBlank()) {
                                        Text(
                                            text = "Description: $description",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }

                                    if (address.isNotBlank()) {
                                        Text(
                                            text = "Address: $address",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }

                                    if (verifiedAt.isNotBlank()) {
                                        Text(
                                            text = "Verified At: $verifiedAt",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }

                                    if (lat != 0.0 || lng != 0.0) {
                                        Text(
                                            text = "Location: $lat, $lng (Accuracy: ${String.format(Locale.getDefault(), "%.1fm", accuracy)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }

                                    if (verificationId > 0) {
                                        Text(
                                            text = "Verification ID: #$verificationId",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Both tabs need a retry after closing, dismissing or completing a scan.
        if (!camera && selectedTab != 2) {
            Button(
                enabled = !interactionBlocked && canCapture,
                onClick = { openCamera() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (candidate == null) "Open camera" else "Scan again") }
        }
        if (selectedTab != 2 && !canCapture && !busy) {
            Text(
                if (summary.version < 0 || summary.count == 0)
                    "Use the menu to sync usable reference photos before scanning."
                else if (target != null && target !in usableAssignments)
                    "The selected record needs usable reference photos. Sync or choose another record."
                else "Use the menu to sync the latest references before scanning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val selectedAssignmentName = remember(target, pending) {
            var name = "Saved Database (All profiles)"
            if (target != null) {
                for (obj in pending.objects()) {
                    if (obj.optLong("subject") == target) {
                        name = obj.optString("subject_name", "Selected Subject")
                        break
                    }
                }
            }
            name
        }

        if (camera) {
            LaunchedEffect(message) {
                if (message.isNotBlank() && (message.contains("No face") || message.contains("No clear") || message.contains(
                        "candidate"
                    ) || message.contains("Please try again"))
                ) {
                    delay(5000L)
                    if (camera) message = ""
                }
            }

            DisposableEffect(lifecycle, cameraController) {
                var attached = true
                var boundCameraInfo: CameraInfo? = null
                cameraReady = false
                val cameraStateObserver = Observer<CameraState> { state ->
                    if (attached && state.error != null) {
                        camera = false
                        cameraReady = false
                        message = "Camera unavailable. Close other camera apps and try again."
                    }
                }
                val initialized = cameraController.initializationFuture
                initialized.addListener({
                    if (attached) {
                        try {
                            initialized.get()
                            check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                "Camera permission is needed for a live verification."
                            }
                            cameraController.bindToLifecycle(lifecycle)
                            boundCameraInfo = cameraController.cameraInfo
                            boundCameraInfo?.cameraState?.observe(lifecycle, cameraStateObserver)
                        } catch (e: Exception) {
                            camera = false
                            cameraReady = false
                            message = e.cause?.localizedMessage ?: e.localizedMessage ?: "Camera unavailable. Please retry."
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        clearScan()
                        permissionHelp = true
                        message = "Camera permission is needed for a live verification."
                    }
                }
                lifecycle.lifecycle.addObserver(observer)
                onDispose {
                    attached = false
                    lifecycle.lifecycle.removeObserver(observer)
                    boundCameraInfo?.cameraState?.removeObserver(cameraStateObserver)
                    cameraController.unbind()
                    cameraReady = false
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        if (selectedTab == 1) "General Scan Mode:" else "Target Subject:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (selectedTab == 1) "All Database Profiles (General Scan)" else selectedAssignmentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { preview ->
                            preview.controller = cameraController
                            preview.previewStreamState.observe(lifecycle) { state ->
                                cameraReady = state == PreviewView.StreamState.STREAMING
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { preview ->
                        preview.previewStreamState.removeObservers(lifecycle)
                        preview.controller = null
                        cameraReady = false
                    },
                )
                if (message.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                            ),
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    enabled = !interactionBlocked,
                    onClick = { clearScan(); message = "" },
                    modifier = Modifier.weight(1f)
                ) { Text("Close camera") }

                Button(
                    enabled = !interactionBlocked && canCapture && cameraReady,
                    onClick = {
                        val requested = scanContext()
                        job {
                            if (!requested.isCurrent(scanContext(), referencesReady()) || !cameraReady) return@job
                            message = "Checking the live capture…"
                            val shot = capture(cameraController)
                            try {
                                val matched =
                                    withContext(Dispatchers.Default) { store.match(shot, requested.subjectId) }
                                if (!requested.isCurrent(scanContext(), referencesReady())) return@job
                                if (matched == null) {
                                    candidate = null
                                    camera = true // keep camera open
                                    message =
                                        "No clear candidate in the saved references. Please try again."
                                } else {
                                    candidate = matched
                                    camera = false
                                    message = "Review the suggested identity."
                                    try { withContext(Dispatchers.IO) { store.flush() } }
                                    catch (_: IOException) { message = "Match saved on this device. Review the suggested identity." }
                                }
                            } finally {
                                shot.recycle()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (cameraReady) "Capture one face" else "Starting camera…") }
            }
        }
        candidate?.let { person ->
            val thumbnail by produceState<Bitmap?>(null, person) {
                value = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(person.thumbnail, 0, person.thumbnail.size)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Suggested candidate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    thumbnail?.let {
                        Image(
                            it.asImageBitmap(),
                            "Reference photo",
                            Modifier.size(130.dp)
                        )
                    }
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        person.category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (person.address.isNotBlank()) {
                        Text(
                            person.address,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (person.description.isNotBlank()) {
                        Text(
                            person.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Check the reference photo and identity independently before confirming. Confirmation records your current location.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        enabled = !interactionBlocked && canCapture,
                        onClick = {
                            val permissions = arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                locationPermissionPending = true
                                locationPermission.launch(permissions)
                            } else job {
                                message = "Getting a fresh location…"
                                val fix = verificationFix(context)
                                withContext(Dispatchers.IO) {
                                    store.queue(
                                        person,
                                        fix.latitude,
                                        fix.longitude,
                                        fix.accuracy,
                                        Instant.now().toString()
                                    )
                                }
                                candidate = null; refreshLocal()
                                message = "Confirmation saved on this device, awaiting upload."
                                try {
                                    updateStatus(); refreshLocal()
                                    message = when {
                                        summary.failed > 0 -> "Review the upload status below."
                                        summary.pending > 0 -> "Confirmation saved on this device, awaiting upload."
                                        else -> "Confirmation delivered."
                                    }
                                } catch (e: IOException) {
                                    message = "Confirmation saved on this device; upload is pending. ${e.localizedMessage.orEmpty()}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Confirm identity and record location") }
                    TextButton(
                        enabled = !interactionBlocked,
                        onClick = {
                            candidate = null; message = "Candidate dismissed."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Leave pending") }
                    TextButton(enabled = !interactionBlocked, onClick = {
                        job {
                            withContext(Dispatchers.IO) { store.reject(person) }
                            candidate = null
                            message = "Rejection saved."
                            try { updateStatus(); refreshLocal() }
                            catch (_: IOException) { message = "Rejection saved on this device, awaiting upload." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Reject this match") }
                }
            }
        }
        if (failures.isNotEmpty()) {
            Text(
                "Confirmations needing review",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            for ((id, reason) in failures) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(
                    enabled = !interactionBlocked,
                    onClick = { job { withContext(Dispatchers.IO) { store.acknowledgeFailure(id) }; refreshLocal() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Acknowledge")
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank() && !camera) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (permissionHelp) {
            TextButton(
                enabled = !interactionBlocked,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                },
            ) { Text("Open permission settings") }
        }
    }
}
