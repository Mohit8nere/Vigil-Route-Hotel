package com.vigil.android.presentation.feature_messaging.data.model

import com.google.gson.annotations.SerializedName

data class ChatUserResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("username") val username: String,
    @SerializedName("role") val role: String,
    @SerializedName("district_id") val districtId: Int? = null,
    @SerializedName("station_id") val stationId: Int? = null,
    @SerializedName("is_admin") val isAdmin: Boolean? = false
)

data class ChatMeResponse(
    @SerializedName("user") val user: ChatUserResponse,
    @SerializedName("group_creator") val isGroupCreator: Boolean
)

data class ChatConversationResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("kind") val kind: String, // "direct" or "group"
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("last_message") val lastMessage: String? = null,
    @SerializedName("last_message_id") val lastMessageId: Long? = null,
    @SerializedName("member_count") val memberCount: Int = 0,
    @SerializedName("unread_count") val unreadCount: Int = 0,
    @SerializedName("is_admin") val isAdmin: Boolean = false,
    @SerializedName("scope_type") val scopeType: String? = null,
    @SerializedName("admins_only_post") val adminsOnlyPost: Boolean = false,
    @SerializedName("members") val members: List<ChatUserResponse>? = null
)

data class CreateConversationRequest(
    @SerializedName("kind") val kind: String,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("member_ids") val memberIds: List<Long>? = null
)

data class ChatMessageResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("conversation_id") val conversationId: Long,
    @SerializedName("sender") val sender: ChatUserResponse,
    @SerializedName("body") val body: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("attachment_type") val attachmentType: String? = null,
    @SerializedName("attachment_url") val attachmentUrl: String? = null,
    @SerializedName("image_size") val imageSize: Long? = 0,
    @SerializedName("client_id") val clientId: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("edited_at") val editedAt: String? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("deleted_by") val deletedBy: Long? = null
)

data class ChatMessageListResponse(
    @SerializedName("results") val results: List<ChatMessageResponse>,
    @SerializedName("next_before_id") val nextBeforeId: Long? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)

data class SendTextMessageRequest(
    @SerializedName("body") val body: String,
    @SerializedName("client_id") val clientId: String
)

data class UpdateMessageRequest(
    @SerializedName("body") val body: String
)

data class ReadStateRequest(
    @SerializedName("message_id") val messageId: Long
)

data class WsTicketResponse(
    @SerializedName("ticket") val ticket: String,
    @SerializedName("ws_path") val wsPath: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("ws_origin") val wsOrigin: String? = null
)

data class ChatEventResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("kind") val type: String,
    @SerializedName("conversation_id") val conversationId: Long? = null,
    @SerializedName("message") val message: ChatMessageResponse? = null,
    @SerializedName("message_id") val messageId: Long? = null,
    @SerializedName("read_message_id") val readMessageId: Long? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class ChatSyncResponse(
    @SerializedName("events") val events: List<ChatEventResponse> = emptyList(),
    @SerializedName("next_after") val latestEventId: Long? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)
