package com.vigil.android.core.common.update

import android.app.Activity
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

class InAppUpdateManager(activity: Activity) {

    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    fun checkForUpdate(updateResultLauncher: ActivityResultLauncher<IntentSenderRequest>) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
            if ((updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) &&
                updateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        updateInfo,
                        updateResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting in-app update flow", e)
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to check for app update", e)
        }
    }

    fun resumeUpdate(updateResultLauncher: ActivityResultLauncher<IntentSenderRequest>) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
            if (updateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        updateInfo,
                        updateResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error resuming in-app update flow", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "InAppUpdateManager"
    }
}
