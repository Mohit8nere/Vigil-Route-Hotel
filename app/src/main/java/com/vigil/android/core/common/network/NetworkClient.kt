package com.vigil.android.core.common.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.common.session.SessionInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClient {
    fun create(context: Context, sessionManager: SessionManager): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(SessionInterceptor(sessionManager))
            .apply {
                if (com.vigil.android.BuildConfig.DEBUG) {
                    val inspector = ChuckerInterceptor.Builder(context)
                        .redactHeaders("Authorization", "Cookie", "Set-Cookie").build()
                    addInterceptor { chain ->
                        val path = chain.request().url.encodedPath
                        if (path.endsWith("/auth/") || path.endsWith("/token/refresh/") || path.endsWith("/ws-ticket/"))
                            chain.proceed(chain.request())
                        else inspector.intercept(chain)
                    }
                }
            }
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
}
