package com.vigil.android.presentation.feature_messaging.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vigil.android.feature_chat.data.repository.ChatRepository
import com.vigil.android.presentation.feature_messaging.data.ChatChannel
import com.vigil.android.presentation.feature_messaging.data.model.ChatUserResponse
import com.vigil.android.utils.toTitleCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ChatFilter {
    ALL, UNREAD, GROUPS
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    application: Application,
    private val chatRepository: ChatRepository
) : AndroidViewModel(application) {

    private val _channels = MutableStateFlow<List<ChatChannel>>(emptyList())
    val channels: StateFlow<List<ChatChannel>> = _channels.asStateFlow()

    private val _currentFilter = MutableStateFlow(ChatFilter.ALL)
    val currentFilter: StateFlow<ChatFilter> = _currentFilter.asStateFlow()

    val error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _roster = MutableStateFlow<List<ChatUserResponse>>(emptyList())
    val roster: StateFlow<List<ChatUserResponse>> = _roster.asStateFlow()

    private val _isGroupCreator = MutableStateFlow(false)
    val isGroupCreator: StateFlow<Boolean> = _isGroupCreator.asStateFlow()

    init {
        chatRepository.startWebSocketAndSync()
        observeRoomConversations()
        refreshFromApi()
        loadChatMe()
    }

    private fun loadChatMe() {
        viewModelScope.launch {
            try {
                val me = chatRepository.getChatMe()
                if (me != null) {
                    _isGroupCreator.value = me.isGroupCreator
                }
            } catch (_: Exception) {}
        }
    }

    private fun observeRoomConversations() {
        viewModelScope.launch {
            chatRepository.getConversationsFlow().collectLatest { entities ->
                val channelList = entities.map { entity ->
                    ChatChannel(
                        id = entity.id.toString(),
                        name = entity.name.toTitleCase(),
                        role = (entity.description ?: (if (entity.kind == "group") "Group Chat" else "Direct Chat")).toTitleCase(),
                        lastMessage = entity.lastMessage ?: "Tap to view messages",
                        timestamp = entity.updatedAt,
                        unreadCount = entity.unreadCount,
                        isOnline = false,
                        kind = entity.kind
                    )
                }
                _channels.value = channelList
            }
        }
    }

    fun setFilter(filter: ChatFilter) {
        _currentFilter.value = filter
    }

    fun refreshFromApi() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                chatRepository.refreshConversations()
                error.value = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) {
                error.value = "Could not refresh chats. Check your connection."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadRoster(query: String? = null) {
        viewModelScope.launch {
            try {
                _roster.value = chatRepository.getRoster(query)
            } catch (_: Exception) {}
        }
    }

    fun startDirectChat(userId: Long, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val convId = chatRepository.startDirectConversation(userId)
            if (convId != null) {
                onCreated(convId)
            } else error.value = "Could not open the conversation. Check the selection and try again."
        }
    }

    fun createGroupChat(name: String, description: String, memberIds: List<Long>, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val convId = chatRepository.createGroupConversation(name, description, memberIds)
            if (convId != null) {
                onCreated(convId)
            } else error.value = "Could not open the conversation. Check the selection and try again."
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
