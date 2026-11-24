package com.vigil.android.presentation.auth.viewmodel

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonParseException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

internal data class LoginFailure(
    val message: String,
    val countCredentialAttempt: Boolean = false,
    val retryAfterSeconds: Int? = null,
)

private fun readableLoginMessage(value: String?): String? = value?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= 500 && !it.contains('<') && !it.contains('>') }

private fun loginBodyMessage(body: String?): String? {
    if (body.isNullOrBlank() || body.length > 16_384) return null
    return runCatching {
        val json = JsonParser.parseString(body)
        if (!json.isJsonObject) return@runCatching null
        fun text(value: JsonElement?): String? = when {
            value == null || value.isJsonNull -> null
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> readableLoginMessage(value.asString)
            value.isJsonArray -> value.asJsonArray.mapNotNull { item ->
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) readableLoginMessage(item.asString) else null
            }.joinToString(" ").takeIf(String::isNotBlank)
            else -> null
        }
        val objectValue = json.asJsonObject
        listOf("detail", "message", "error", "non_field_errors").firstNotNullOfOrNull { key -> text(objectValue.get(key)) }
            ?: listOf("username", "password").firstNotNullOfOrNull { key ->
                text(objectValue.get(key))?.let { "$key: $it" }
            }
    }.getOrNull()
}

/** Count only a server-confirmed credential rejection, never a transport/configuration failure. */
internal fun classifyLoginFailure(
    status: Int,
    responseMessage: String? = null,
    errorBody: String? = null,
    retryAfter: String? = null,
): LoginFailure {
    val detail = loginBodyMessage(errorBody) ?: readableLoginMessage(responseMessage)
    if (status == 429) {
        val seconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1, 604_800)?.toInt() ?: 60
        return LoginFailure(detail ?: "Too many login requests. Please wait before trying again.", retryAfterSeconds = seconds)
    }
    if (status >= 500) return LoginFailure("Vigil login is temporarily unavailable (HTTP $status). Please retry shortly.")
    val normalized = detail?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)
    val invalidCredentials = status in setOf(200, 400, 401, 403) && normalized in setOf(
        "invalid credentials", "invalid username or password", "incorrect username or password",
    )
    if (invalidCredentials) return LoginFailure("Invalid username or password.", countCredentialAttempt = true)
    return LoginFailure(detail ?: when (status) {
        400 -> "The server rejected the login request (HTTP 400). Check the server address and backend configuration."
        401, 403 -> "Login was not permitted. Check your account with the administrator."
        404 -> "The login address was not found. Check the app server address."
        in 200..299 -> "The server returned an incomplete login response. Please retry or contact the administrator."
        else -> "Login could not be completed (HTTP $status). Please retry."
    })
}

internal fun loginTransportMessage(error: Throwable): String = when (error) {
    is SocketTimeoutException -> "The Vigil server took too long to respond. Check the connection and retry."
    is UnknownHostException, is ConnectException -> "Could not reach the Vigil server. Check the network and server address."
    is SSLException -> "The secure connection failed. Check the server address and certificate."
    is JsonParseException -> "The server returned an unexpected login response. Check the server address and backend."
    is IOException -> "The login connection was interrupted. Check the network and retry."
    else -> "Login could not be completed. Please retry or contact the administrator."
}
