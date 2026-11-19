package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vigil.android.R
import com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs
import java.io.File

class UserDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_detail)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val patrolIdText = findViewById<TextView>(R.id.patrolIdText)
        val nameText = findViewById<TextView>(R.id.nameText)
        val roleText = findViewById<TextView>(R.id.roleText)
        val faceImage = findViewById<ImageView>(R.id.faceImage)

        val prefs = Prefs(this)

        // ✅ TEXT DATA
        patrolIdText.text = "Patrol ID: ${prefs.getPatrolId()}"
        nameText.text = "Name: ${prefs.getUserName() ?: "N/A"}"
        roleText.text = "Role: ${prefs.getUserRole() ?: "N/A"}"

        // ✅ LOAD IMAGE (FILE FIRST)
        val file = File(filesDir, "profile.jpg")

        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            faceImage.setImageBitmap(bitmap)
        } else {
            // ✅ FALLBACK: BASE64 (if you stored somewhere)
            val base64 = prefs.getProfileImageBase64()

            if (!base64.isNullOrEmpty()) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    faceImage.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    faceImage.setImageResource(android.R.drawable.ic_menu_camera)
                }
            } else {
                faceImage.setImageResource(android.R.drawable.ic_menu_camera)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}