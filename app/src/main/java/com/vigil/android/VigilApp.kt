package com.vigil.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VigilApp : Application(), androidx.work.Configuration.Provider {
    @javax.inject.Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory
    @javax.inject.Inject lateinit var chats: dagger.Lazy<com.vigil.android.feature_chat.data.repository.ChatRepository>

    override fun onCreate() {
        super.onCreate()
        // The worker handles background catch-up. A single socket serves all foreground activities.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            var started = 0
            override fun onActivityStarted(a: android.app.Activity) { if (++started == 1) chats.get().startWebSocketAndSync() }
            override fun onActivityStopped(a: android.app.Activity) { if (--started == 0) chats.get().stopWebSocket() }
            override fun onActivityCreated(a: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, out: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setWorkerFactory(workerFactory).build()
}
