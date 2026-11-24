package com.vigil.android.core.di.api

import com.vigil.android.presentation.feature_messaging.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ChatApiService {

    @GET(ApiNames.CHAT_ME_API)
    suspend fun getChatMe(): Response<ChatMeResponse>

    @GET(ApiNames.CHAT_ROSTER_API)
    suspend fun getRoster(
        @Query("q") query: String? = null
    ): Response<List<ChatUserResponse>>

    @GET(ApiNames.CHAT_CONVERSATIONS_API)
    suspend fun getConversations(@Query("paged") paged: Int = 1, @Query("before_id") before: Long? = null): Response<List<ChatConversationResponse>>

    @POST(ApiNames.CHAT_CONVERSATIONS_API)
    suspend fun createConversation(
        @Body request: CreateConversationRequest
    ): Response<ChatConversationResponse>

    @GET("api/v1/chat/conversations/{id}/")
    suspend fun getConversationDetails(
        @Path("id") conversationId: Long
    ): Response<ChatConversationResponse>

    @GET("api/v1/chat/conversations/{id}/messages/")
    suspend fun getMessages(
        @Path("id") conversationId: Long,
        @Query("limit") limit: Int = 40,
        @Query("before_id") beforeId: Long? = null
    ): Response<ChatMessageListResponse>

    @POST("api/v1/chat/conversations/{id}/messages/")
    suspend fun sendTextMessage(
        @Path("id") conversationId: Long,
        @Body request: SendTextMessageRequest,
        @Header("X-Vigil-Local-Owner") owner: String? = null
    ): Response<ChatMessageResponse>

    @Multipart
    @POST("api/v1/chat/conversations/{id}/messages/")
    suspend fun sendImageMessage(
        @Path("id") conversationId: Long,
        @Part image: MultipartBody.Part,
        @Part("body") body: RequestBody,
        @Part("client_id") clientId: RequestBody,
        @Header("X-Vigil-Local-Owner") owner: String? = null
    ): Response<ChatMessageResponse>

    @PATCH("api/v1/chat/messages/{id}/")
    suspend fun editMessage(
        @Path("id") messageId: Long,
        @Body request: UpdateMessageRequest
    ): Response<ChatMessageResponse>

    @DELETE("api/v1/chat/messages/{id}/")
    suspend fun deleteMessage(
        @Path("id") messageId: Long,
        @Query("mode") mode: String // "me" or "everyone"
    ): Response<Map<String, Any>>

    @POST("api/v1/chat/conversations/{id}/read/")
    suspend fun markAsRead(
        @Path("id") conversationId: Long,
        @Body request: ReadStateRequest
    ): Response<Map<String, Any>>

    @POST(ApiNames.CHAT_WS_TICKET_API)
    suspend fun getWsTicket(): Response<WsTicketResponse>

    @GET(ApiNames.CHAT_SYNC_API)
    suspend fun getSyncEvents(
        @Query("after") afterEventId: Long? = null
    ): Response<ChatSyncResponse>
}
