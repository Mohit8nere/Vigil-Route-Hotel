package com.vigil.android.presentation.feature_messaging.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vigil.android.feature_chat.data.local.entities.MessageEntity
import com.vigil.android.features.fieldops.CameraButton
import com.vigil.android.presentation.feature_messaging.viewmodel.MessagingViewModel
import kotlinx.coroutines.*
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessagingScreen(onBack: () -> Unit, viewModel: MessagingViewModel, threadId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by viewModel.messages.collectAsState()
    val conversations by remember(viewModel) { viewModel.conversations }.collectAsState(emptyList())
    val more by viewModel.more.collectAsState()
    val error by viewModel.error.collectAsState()
    val snack = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    var draft by rememberSaveable(threadId) { mutableStateOf("") }
    var attachments by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MessageEntity?>(null) }
    var forward by remember { mutableStateOf<MessageEntity?>(null) }
    var edit by remember { mutableStateOf<MessageEntity?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var prepareRecording by remember { mutableStateOf<Job?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var queued by remember { mutableStateOf(false) }
    val channel = conversations.firstOrNull { it.id == threadId }
    LaunchedEffect(threadId) {
        viewModel.loadMessages(threadId)
        (context as? ComponentActivity)?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.foreground(true)
            try { awaitCancellation() } finally { viewModel.foreground(false) }
        }
    }
    LaunchedEffect(error) { error?.let { queued = false; snack.showSnackbar(it); viewModel.error.value = null } }
    val wantedScroll = messages.lastOrNull()?.localId
    LaunchedEffect(wantedScroll) {
        if (messages.isNotEmpty() && (messages.last().isOutgoing || list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= messages.size - 3 } != false))
            list.animateScrollToItem(messages.lastIndex + if (more) 1 else 0)
    }
    fun send(type: String, uri: Uri? = null, temp: File? = null) {
        if (queued) return
        queued = true
        viewModel.send(draft, type, uri) { queued = false; draft = ""; temp?.delete() }
        attachments = false
    }
    fun endRecording(deliver: Boolean) {
        val rec = recorder ?: return
        recorder = null
        val file = recordingFile
        recordingFile = null
        val ok = runCatching { rec.stop() }.isSuccess
        rec.release()
        if (ok && deliver && file != null) send("AUDIO", Uri.fromFile(file), file) else {
            file?.delete()
            if (deliver) viewModel.error.value = "Recording was too short. Hold the microphone for a moment."
        }
    }
    fun beginRecording() {
        if (recorder != null || prepareRecording?.isActive == true) return
        prepareRecording = scope.launch {
            val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
            @Suppress("DEPRECATION") val rec = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            var handedOff = false
            try {
                withContext(Dispatchers.IO) {
                    rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(32000)
                    rec.setAudioSamplingRate(22050)
                    rec.setOutputFile(file.absolutePath)
                    rec.prepare()
                    rec.start()
                }
                seconds = 0; recordingFile = file; recorder = rec
                handedOff = true
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { viewModel.error.value = "Microphone unavailable."
            } finally { if (!handedOff) { rec.release(); file.delete() } }
        }
    }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted ->
        if (permitted) beginRecording() else viewModel.error.value = "Allow microphone access to record a voice message."
    }
    val latestCancel by rememberUpdatedState { prepareRecording?.cancel(); endRecording(false) }
    DisposableEffect(Unit) {
        val owner = context as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latestCancel() }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); latestCancel() }
    }
    LaunchedEffect(recorder) {
        if (recorder != null) {
            while (seconds < 110) { delay(1000); seconds++ }
            endRecording(true)
        }
    }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> send("IMAGE", uri) } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> send("DOCUMENT", uri) } }
    fun open(msg: MessageEntity) {
        scope.launch {
            try {
                val file = viewModel.media(msg)
                val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
                val mime = when (msg.mediaType) { "DOCUMENT" -> "application/pdf"; "AUDIO" -> "audio/mp4"; else -> "image/*" }
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { snack.showSnackbar(e.localizedMessage ?: "Cannot open attachment.") }
        }
    }
    Scaffold(modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snack) }, topBar = {
        TopAppBar(title = { Column {
            Text(channel?.name ?: "Conversation", maxLines = 1)
            Text(if (channel?.kind == "group") "Group chat" else "Direct chat", style = MaterialTheme.typography.labelMedium)
        } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
            AnimatedVisibility(attachments) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { image.launch("image/*") }, label = { Text("Gallery") })
                    CameraButton("Camera", context.cacheDir, !queued, onError = { viewModel.error.value = it }) { send("IMAGE", Uri.fromFile(it), it) }
                    AssistChip(onClick = { pdf.launch("application/pdf") }, label = { Text("PDF") })
                }
            }
            if (recorder != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recording · ${seconds}s", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { endRecording(false) }) { Text("Cancel") }
                Button(onClick = { endRecording(true) }) { Text("Send voice note") }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { attachments = !attachments }, enabled = !queued) { Icon(Icons.Default.AttachFile, "Attach") }
                OutlinedTextField(draft, { if (it.length <= 4000) draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }, maxLines = 4, shape = RoundedCornerShape(22.dp))
                if (draft.isNotBlank()) IconButton(onClick = { send("TEXT") }, enabled = !queued) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                } else IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                    else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !queued) { Icon(Icons.Default.Mic, "Record voice message", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }) { pads ->
        LazyColumn(Modifier.fillMaxSize().padding(pads), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (more) item { TextButton(onClick = { viewModel.older() }) { Text("Load earlier messages") } }
            if (messages.isEmpty()) item { Text("No messages yet", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            items(messages, key = { it.localId }) { msg ->
                Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = if (msg.isOutgoing) Arrangement.End else Arrangement.Start) {
                    Surface(shape = RoundedCornerShape(16.dp), color = if (msg.isOutgoing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.widthIn(max = 292.dp).combinedClickable(onClick = { if (msg.deletedAt == null && msg.mediaType != "TEXT") open(msg) },
                            onLongClick = { if (msg.deletedAt == null) selected = msg })) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (!msg.isOutgoing && channel?.kind == "group") Text(msg.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (msg.deletedAt != null) Text("Message removed") else {
                                if (msg.mediaType == "IMAGE") InlinePhoto(msg, viewModel)
                                if (msg.mediaType == "AUDIO") Text("▶  Play voice note")
                                if (msg.mediaType == "DOCUMENT") Text("Open PDF document")
                                if (!msg.body.isNullOrEmpty()) Text(msg.body)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(runCatching { OffsetDateTime.parse(msg.createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM · HH:mm")) }.getOrDefault(""), style = MaterialTheme.typography.labelSmall)
                                if (msg.isOutgoing) {
                                    val icon = when (msg.deliveryStatus) { "SENT" -> Icons.Default.Check; "FAILED" -> Icons.Default.ErrorOutline; else -> Icons.Default.Schedule }
                                    Icon(icon, msg.deliveryStatus, Modifier.size(15.dp))
                                    if (msg.deliveryStatus != "SENT") Text(when(msg.deliveryStatus) { "FAILED" -> "Failed"; "SENDING" -> "Sending"; else -> "Queued" }, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (msg.deliveryStatus == "FAILED") TextButton(onClick = { viewModel.retry(msg.clientId) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
    selected?.let { msg ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Message actions") }, text = {
            Column {
                if (msg.isOutgoing && msg.serverMessageId != null && msg.mediaType == "TEXT") TextButton(onClick = { selected = null; edit = msg }) { Text("Edit") }
                TextButton(onClick = { selected = null; forward = msg }) { Text("Forward to chat") }
                if (msg.serverMessageId != null) {
                    TextButton(onClick = { selected = null; viewModel.delete(msg, false) }) { Text("Delete for me") }
                    if (msg.isOutgoing) TextButton(onClick = { selected = null; viewModel.delete(msg, true) }) { Text("Delete for everyone") }
                }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    edit?.let { msg ->
        var text by remember(msg.localId) { mutableStateOf(msg.body.orEmpty()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit message") }, text = { OutlinedTextField(text, { text = it }) },
            confirmButton = { TextButton(onClick = { msg.serverMessageId?.let { viewModel.edit(it, text) }; edit = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    forward?.let { msg ->
        AlertDialog(onDismissRequest = { forward = null }, title = { Text("Forward to") }, text = {
            LazyColumn { items(conversations, key = { it.id }) { dest ->
                TextButton(onClick = {
                    forward = null
                    viewModel.guarded {
                        val file = if (msg.mediaType == "TEXT") null else viewModel.media(msg)
                        viewModel.send(msg.body.orEmpty(), msg.mediaType, file?.let { Uri.fromFile(it) }, dest.id)
                    }
                }) { Text(dest.name) }
            } }
        }, confirmButton = { TextButton(onClick = { forward = null }) { Text("Cancel") } })
    }
}

@Composable
private fun InlinePhoto(msg: MessageEntity, viewModel: MessagingViewModel) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, msg.imageUrl, msg.localMediaPath) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = viewModel.media(msg)
                try { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
                finally { if (msg.imageUrl != null) file.delete() }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
    }
    image?.let { Image(it, "Attached photo", Modifier.widthIn(max = 255.dp).heightIn(max = 220.dp)) } ?: Text("Open photo")
}
