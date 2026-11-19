package com.vigil.android.presentation.verification

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
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
private suspend fun verificationFix(context: Context): Location = suspendCancellableCoroutine { result ->
    val token = CancellationTokenSource()
    result.invokeOnCancellation { token.cancel() }
    val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
        .setMaxUpdateAgeMillis(0).setDurationMillis(30_000L).build()
    LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request,token.token)
        .addOnSuccessListener { fix ->
            if (!result.isActive) return@addOnSuccessListener
            if (fix == null || !fix.hasAccuracy() || SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos > 60_000_000_000L) {
                result.resumeWithException(IllegalStateException("Current location unavailable. Move to an open area and retry."))
            } else result.resume(fix)
        }.addOnFailureListener { if (result.isActive) result.resumeWithException(it) }
}

private suspend fun capture(controller: LifecycleCameraController): Bitmap =
    suspendCancellableCoroutine { result ->
        val executor = Executors.newSingleThreadExecutor()
        try {
            controller.takePicture(executor,object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val original = image.toBitmap()
                        val degrees = image.imageInfo.rotationDegrees
                        val ratio = minOf(1f, 1280f / maxOf(original.width,original.height))
                        val transform = Matrix().apply { postScale(ratio,ratio); postRotate(degrees.toFloat()) }
                        val upright = Bitmap.createBitmap(original,0,0,original.width,original.height,transform,true)
                        if (upright !== original) original.recycle()
                        if (result.isActive) result.resume(upright) else upright.recycle()
                    } catch (e: Exception) {
                        if (result.isActive) result.resumeWithException(e)
                    } finally { image.close(); executor.shutdown() }
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
    DisposableEffect(store) { onDispose { store.close() } }
    var summary by remember { mutableStateOf(LocalSummary(-1,0,0,0,"")) }
    var status by remember { mutableStateOf(JSONObject()) }
    var failures by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
    var target by remember { mutableStateOf<Long?>(null) }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    val cameraController = remember { LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
    } }
    fun job(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation() }
            catch (e: Exception) { message = e.localizedMessage ?: "Please retry." }
            finally { busy = false }
        }
    }
    suspend fun refreshLocal() {
        val local = withContext(Dispatchers.IO) { Triple(store.summary(),store.state("status"),store.failedEvents()) }
        summary = local.first
        status = local.second
        failures = local.third
    }
    suspend fun updateStatus() {
        withContext(Dispatchers.IO) {
            store.flush()
            VerificationJobs.publish(context,store,store.refreshStatus())
        }
    }

    LaunchedEffect(Unit) {
        VerificationJobs.schedule(context)
        refreshLocal()
        try { updateStatus() }
        catch (e: Exception) { message = "Using the saved reference database. ${e.localizedMessage.orEmpty()}" }
        refreshLocal()
    }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) job { updateStatus(); refreshLocal() }
        else message = "Assignments and database updates remain visible on this screen."
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        camera = it
        if (!it) message = "Camera permission is needed for a live verification."
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = if (it.values.any { granted -> granted }) "Location available. Confirm after reviewing the candidate."
                  else "Location permission is required to record the verification site."
    }
    val canCapture = summary.count > 0 && (status.optLong("version",summary.version) == summary.version)
    val pending = status.optJSONArray("assignments") ?: JSONArray()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Face verification",style=MaterialTheme.typography.headlineSmall)
        Text(if (summary.version < 0) "Sync once to enable offline matching."
            else "Saved database v${summary.version} · ${summary.count} profiles · ${summary.pending} awaiting upload")
        if (status.has("version") && status.optLong("version") != summary.version) Text("An updated reference database is available.")
        if (summary.unusable.isNotBlank()) Text("Reference photos need correction for: ${summary.unusable}")
        OutlinedButton(enabled=!busy,onClick={ job {
            candidate=null; camera=false; message="Checking for reference updates…"
            withContext(Dispatchers.IO) {
                store.sync { done,total -> scope.launch { message="Preparing references $done / $total" } }
            }
            refreshLocal()
            message="Reference database updated."
            updateStatus(); refreshLocal()
        } }) { Text("Sync reference database") }
        OutlinedButton(enabled=!busy,onClick={ job { updateStatus(); refreshLocal(); message="Assignments and upload status refreshed." } }) { Text("Refresh assignments and uploads") }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            TextButton(onClick={ notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Enable update alerts") }
        }
        Text("Verification assignments",style=MaterialTheme.typography.titleMedium)
        if (pending.length() == 0) Text("No pending assignments on this device.")
        for (assignment in pending.objects()) {
            val id=assignment.getLong("subject")
            OutlinedButton(enabled=!busy,onClick={ target=id; candidate=null; message="" }) {
                Text("${if(target==id) "Selected · " else ""}${assignment.getString("subject_name")} · due ${assignment.getString("due_date")}")
            }
        }
        if (target != null) TextButton(enabled=!busy,onClick={ target=null; candidate=null }) { Text("Use the whole saved database") }
        Text(if (target == null) "Compare against the saved database." else "Compare against the selected assignment.")
        if (!camera) Button(enabled=!busy && canCapture,onClick={
            message=""; candidate=null
            if (ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) camera=true
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }) { Text("Open camera") }
        if (camera) {
            DisposableEffect(lifecycle,cameraController) {
                try { cameraController.bindToLifecycle(lifecycle) }
                catch (e: Exception) { message=e.localizedMessage ?: "Camera unavailable." }
                onDispose { cameraController.unbind() }
            }
            AndroidView(factory={ ctx -> PreviewView(ctx).also { it.controller=cameraController } },
                modifier=Modifier.fillMaxWidth().height(300.dp))
            Button(enabled=!busy,onClick={ job {
                candidate=null; message="Checking the live capture…"
                val shot=capture(cameraController)
                try { candidate=withContext(Dispatchers.Default) { store.match(shot,target) } }
                finally { shot.recycle() }
                camera=false
                message=if (candidate==null) "No clear candidate. No verification recorded." else "Review the suggested identity."
            } }) { Text("Capture one face") }
            TextButton(enabled=!busy,onClick={ camera=false }) { Text("Close camera") }
        }
        candidate?.let { person ->
            val thumbnail=remember(person) { BitmapFactory.decodeByteArray(person.thumbnail,0,person.thumbnail.size) }
            Card { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Suggested candidate",style=MaterialTheme.typography.titleMedium)
                thumbnail?.let { Image(it.asImageBitmap(),"Reference photo",Modifier.size(130.dp)) }
                Text(person.name,style=MaterialTheme.typography.titleLarge)
                Text(person.category)
                if (person.address.isNotBlank()) Text(person.address)
                if (person.description.isNotBlank()) Text(person.description)
                Text("Check the reference photo and identity independently before confirming. Confirmation records your current location.")
                Button(enabled=!busy,onClick={
                    val permissions=arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (permissions.none { ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED }) {
                        locationPermission.launch(permissions)
                    } else job {
                        message="Getting a fresh location…"
                        val fix=verificationFix(context)
                        withContext(Dispatchers.IO) { store.queue(person,fix.latitude,fix.longitude,fix.accuracy,Instant.now().toString()) }
                        candidate=null; refreshLocal()
                        message="Confirmation saved on this device, awaiting upload."
                        try { updateStatus(); refreshLocal()
                            message=if (summary.failed>0) "Review the upload status below." else "Confirmation delivered."
                        } catch (_: IOException) { /* Job retries when online. */ }
                    }
                }) { Text("Confirm identity and record location") }
                TextButton(enabled=!busy,onClick={ candidate=null; message="Candidate dismissed." }) { Text("Dismiss candidate") }
            } }
        }
        if (failures.isNotEmpty()) {
            Text("Confirmations needing review", style=MaterialTheme.typography.titleMedium)
            for ((id,reason) in failures) {
                Text(reason)
                TextButton(enabled=!busy,onClick={ job { withContext(Dispatchers.IO) { store.acknowledgeFailure(id) }; refreshLocal() } }) { Text("Acknowledge") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message)
    }
}
