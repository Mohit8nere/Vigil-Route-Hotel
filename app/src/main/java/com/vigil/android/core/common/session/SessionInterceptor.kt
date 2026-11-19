package com.vigil.android.core.common.session

import com.vigil.android.core.di.api.ApiNames
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class SessionInterceptor(private val session: SessionManager) : Interceptor {
    companion object {
        private val renewalLock = Any()
        private val renewalClient = OkHttpClient()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val origin = ApiNames.BASE_URL.toHttpUrl()
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            url.encodedPath == "/api/v1/officers/mobile/auth/") return chain.proceed(request)
        val previous = session.fetchAuthToken() ?: return chain.proceed(request)
        val account = session.getUserId()
        val role = session.getRole()
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $previous").build())
        if (response.code != 401) return response

        val access = synchronized(renewalLock) {
            if (session.getUserId() != account || session.getRole() != role) null
            else if (session.fetchAuthToken() != previous) session.fetchAuthToken()
            else renew(previous)
        }
        if (access == null || request.body?.isOneShot() == true) return response
        response.close()
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $access").build())
    }

    private fun renew(previous: String): String? {
        val refresh = session.fetchRefreshToken()
        if (refresh.isNullOrBlank()) {
            session.expireIfCurrent(previous)
            return null
        }
        val json = JSONObject().put("refresh", refresh).toString()
        val call = Request.Builder().url(ApiNames.BASE_URL + "api/v1/auth/token/refresh/")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        return try {
            renewalClient.newCall(call).execute().use {
                if (!it.isSuccessful) {
                    if (it.code == 401 || it.code == 403) session.expireIfCurrent(previous)
                    return null
                }
                val body = JSONObject(it.body?.string() ?: return null)
                val access = body.getString("access")
                if (session.replaceTokens(previous, access, body.optString("refresh", refresh))) access else null
            }
        } catch (_: IOException) { null }
        catch (_: org.json.JSONException) { null }
    }
}
