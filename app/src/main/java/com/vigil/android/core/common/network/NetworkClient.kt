package com.vigil.android.core.common.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.common.session.SessionInterceptor
import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkClient {

    /**
     * TEMPORARY VAPT OVERRIDE: Disables SSL/TLS Certificate & Hostname verification
     * to allow proxying and interception tools (Burp Suite, OWASP ZAP, Charles).
     * Comment out or remove this call after VAPT testing is completed.
     */
    fun applyVaptSslBypass(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return builder
    }

    fun create(context: Context, sessionManager: SessionManager): OkHttpClient {
        val builder = OkHttpClient.Builder()
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

        // VAPT TEMPORARY: Bypass SSL/TLS Verification
        applyVaptSslBypass(builder)

        return builder.build()
    }
}
