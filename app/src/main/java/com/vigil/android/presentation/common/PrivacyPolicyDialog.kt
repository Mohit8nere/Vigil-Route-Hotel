package com.vigil.android.presentation.common

import android.app.AlertDialog
import android.content.Context

object PrivacyPolicyDialog {
    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Privacy Policy & Data Usage")
            .setMessage(
                "Vigil collects and processes data to ensure secure attendance and patrol verification:\n\n" +
                        "• Location Data: Collected in the background and foreground to track patrol routes, verify checkpoints, and log attendance.\n" +
                        "• Camera & Photos: Used for facial recognition, identity verification, and submitting patrol evidence.\n" +
                        "• Data Transmission: All collected data is securely transmitted to your organization's authorized servers.\n\n" +
                        "You can review your privacy settings or revoke permissions at any time in device settings."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
