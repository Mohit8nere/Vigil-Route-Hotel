package com.vigil.android.presentation.feature_messaging.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.android.feature_chat.data.repository.ChatRepository
import com.vigil.android.feature_chat.data.local.entities.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class MessagingViewModel @Inject constructor(private val repository: ChatRepository) : ViewModel() {
    val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val conversations = repository.getConversationsFlow()
    val error = MutableStateFlow<String?>(null)
    val more = MutableStateFlow(false)
    private var active = 0L
    private var visible = false
    private var readId = 0L
    private var loadJob: Job? = null

    fun loadMessages(id: Long) {
        if (id <= 0 || id == active) return
        active = id
        readId = 0
        loadJob?.cancel()
        if (visible) repository.showThread(id)
        loadJob = viewModelScope.launch {
            launch {
                repository.getMessagesFlow(id).collect {
                    messages.value = it
                    readLatest()
                }
            }
            guarded { more.value = repository.refreshMessages(id) }
        }
    }
    private var readJob: Job? = null
    private fun readLatest() {
        val latest = messages.value.filterNot { it.isOutgoing }.mapNotNull { it.serverMessageId }.maxOrNull() ?: return
        if (!visible || latest <= readId || readJob?.isActive == true) return
        readJob = viewModelScope.launch {
            try {
                if (repository.markAsRead(active, latest)) {
                    readId = latest
                    if (visible && messages.value.any { !it.isOutgoing && (it.serverMessageId ?: 0) > latest }) {
                        readJob = null; readLatest()
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    fun foreground(value: Boolean) {
        visible = value
        if (value) repository.startWebSocketAndSync()
        repository.showThread(active.takeIf { value && it > 0 })
        if (value) readLatest() else readJob?.cancel()
    }
    fun older() = guarded {
        val before = messages.value.mapNotNull { it.serverMessageId }.minOrNull() ?: return@guarded
        more.value = repository.refreshMessages(active, before)
    }
    fun send(body: String, type: String = "TEXT", uri: Uri? = null, target: Long = active, onQueued: () -> Unit = {}) = guarded {
        repository.sendMessageLocallyAndEnqueue(target, body.trim(), type, uri?.toString())
        onQueued()
    }
    fun retry(id: String) = guarded { repository.retryFailedMessage(id) }
    fun delete(msg: MessageEntity, all: Boolean) = guarded { repository.deleteMessage(msg.localId, msg.serverMessageId, all) }
    fun edit(id: Long, text: String) = guarded { repository.editMessage(id, text) }
    suspend fun media(msg: MessageEntity) = repository.mediaFile(requireNotNull(msg.imageUrl ?: msg.localMediaPath), msg.mediaType)
    fun guarded(action: suspend () -> Unit): Job = viewModelScope.launch {
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { error.value = e.localizedMessage ?: "Chat request failed." }
    }
    override fun onCleared() { repository.showThread(null); super.onCleared() }
}
