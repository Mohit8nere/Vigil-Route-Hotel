package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.vigil.android.MainActivity
import com.vigil.android.R
import com.vigil.android.core.common.network.NetworkClient
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiNames.BASE_URL
import com.vigil.android.core.di.repository.AuthRepository
import com.vigil.android.presentation.feature_attendance.service.PatrolForegroundService
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope

@AndroidEntryPoint
class RoutesActivity : AppCompatActivity() {

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var chatRepository: com.vigil.android.feature_chat.data.repository.ChatRepository

    private lateinit var prefs: Prefs
    private var gpsRequiredDialog: AlertDialog? = null

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // GPS turned on smoothly
            gpsRequiredDialog?.dismiss()
            checkPermissionsAndStartService()
        } else {
            // User denied turning on GPS
            showGpsRequiredDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // window.setFlags(
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE,
        //     android.view.WindowManager.LayoutParams.FLAG_SECURE
        // )
        setContentView(R.layout.activity_routes)

        prefs = Prefs(this)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        val adapter = RoutesPagerAdapter(this)
        viewPager.adapter = adapter
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh)
        val btnLogout = findViewById<ImageButton>(R.id.btnLogout)
        val btnChatContainer = findViewById<android.view.View>(R.id.btnChatContainer)
        val btnChat = findViewById<ImageButton>(R.id.btnChat)
        val tvChatUnreadBadge = findViewById<android.widget.TextView>(R.id.tvChatUnreadBadge)

        val openChatAction = {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("start_destination", "traffic_chat")
            }
            startActivity(intent)
        }

        btnChatContainer.setOnClickListener { openChatAction() }
        btnChat.setOnClickListener { openChatAction() }

        lifecycleScope.launchWhenStarted {
            chatRepository.getTotalUnreadCountFlow().collect { unread ->
                val count = unread ?: 0
                if (count > 0) {
                    tvChatUnreadBadge.visibility = android.view.View.VISIBLE
                    tvChatUnreadBadge.text = if (count > 99) "99+" else count.toString()
                } else {
                    tvChatUnreadBadge.visibility = android.view.View.GONE
                }
            }
        }

        chatRepository.startWebSocketAndSync()

        findViewById<android.view.View>(R.id.dutyMenu).setOnClickListener { anchor ->
            android.widget.PopupMenu(this, anchor).apply {
                menu.add("Traffic duties").setOnMenuItemClickListener {
                    startActivity(Intent(this@RoutesActivity, MainActivity::class.java).putExtra("start_destination", "traffic_duties"))
                    true
                }
                menu.add("VIP duties").setOnMenuItemClickListener {
                    startActivity(Intent(this@RoutesActivity, com.vigil.android.presentation.vip.VipDutyActivity::class.java))
                    true
                }
            }.show()
        }
        btnBack.setOnClickListener {
            showExitDialog()
        }

        btnRefresh.setOnClickListener {
            val currentFragment = supportFragmentManager
                .findFragmentByTag("f${viewPager.currentItem}")

            if (currentFragment is CurrentRouteFragment) {
                currentFragment.refreshRoutes()
            } else if (currentFragment is PreviousRouteFragment) {
                currentFragment.refreshRoutes()
            }
        }

        btnLogout.setOnClickListener {
            showLogoutConfirmationDialog()
        }
        tabLayout.tabMode = com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.current_route)
                1 -> getString(R.string.previous_route)
                2 -> "Face Match"
                else -> "Assigned surveys"
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                viewPager.isUserInputEnabled = (position != 2)
            }
        })

        // Always check permissions after login to ensure notifications are enabled
        checkPermissionsAndStartService()
        checkGpsStatus()

        callRadiusApi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })
    }

    private fun checkGpsStatus() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            // GPS is already on
            gpsRequiredDialog?.dismiss()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    gpsResolutionLauncher.launch(intentSenderRequest)
                } catch (_: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            } else {
                showGpsRequiredDialog()
            }
        }
    }

    private fun showGpsRequiredDialog() {
        if (gpsRequiredDialog?.isShowing == true) return

        gpsRequiredDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.gps_required))
            .setMessage(getString(R.string.this_application_requires_gps_to_be_enabled_for_patrolling_please_turn_it_on_to_continue))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.turn_on)) { _, _ ->
                checkGpsStatus()
            }
            .setNegativeButton(getString(R.string.exit)) { _, _ ->
                finishAffinity()
            }
            .show()
    }

    private fun callRadiusApi() {
        val token = prefs.getToken()
        if (token.isNullOrEmpty()) return

        val client = NetworkClient.create(this, sessionManager)
        val request = Request.Builder()
            .url("${BASE_URL}api/v1/settings/radius/")
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@RoutesActivity,
                        getString(R.string.could_not_refresh_settings), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string()
                try {
                    val obj = JSONObject(res)
                    val rawRadius = obj.getJSONObject(getString(R.string.data)).getString(
                        getString(
                            R.string.setting_value
                        ))
                    val cleanRadius = rawRadius.replace("m", "", ignoreCase = true).trim()
                    prefs.saveRadius(cleanRadius)
                } catch (e: Exception) {
                    // Use cached value
                }
            }
        })
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            PatrolForegroundService.startPatrol(this)
        } else {
            showLocationDisclosureDialog(missingPermissions.toTypedArray())
        }
    }

    private fun showLocationDisclosureDialog(permissionsToRequest: Array<String>) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.location_background_tracking_notice))
            .setMessage(getString(R.string.vigil_collects_precise_location_data_to_track_security_patrols_verify_checkpoints_and_record_attendance_even_when_the_app_is_running_in_the_background_or_closed_this_data_is_required_for_your_security_shift_operations))
            .setPositiveButton(getString(R.string.continue_string)) { _, _ ->
                permissionLauncher.launch(permissionsToRequest)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                Toast.makeText(this,
                    getString(R.string.location_permission_is_required_for_patrol_tracking), Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted) {
            PatrolForegroundService.startPatrol(this)
        } else {
            Toast.makeText(this,
                getString(R.string.location_permission_required_for_patrol), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        checkGpsStatus()
        PatrolForegroundService.notifyAppForeground(this)
    }

    override fun onPause() {
        super.onPause()
        PatrolForegroundService.notifyAppBackground(this)
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.exit_app))
            .setMessage(getString(R.string.are_you_sure_you_want_to_exit_the_app))
            .setPositiveButton(getString(R.string.exit)) { _, _ ->
                finishAffinity()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.logout))
            .setMessage(getString(R.string.are_you_sure_you_want_to_logout))
            .setPositiveButton(getString(R.string.logout)
            ) { _, _ ->
                authRepository.logout()
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
