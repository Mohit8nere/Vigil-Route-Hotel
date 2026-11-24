package com.vigil.android.presentation.feature_messaging.data

data class ChatChannel(
    val id: String,
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
    val lastMessage: String,
    val timestamp: String,
    val unreadCount: Int = 0,
    val isOnline: Boolean = true,
    val kind: String = "direct" // "direct" or "group"
)
