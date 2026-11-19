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
            .addInterceptor(ChuckerInterceptor(context))
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
}
