package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vigil.android.R
import com.vigil.android.core.common.`object`.SecureTime
import com.vigil.android.presentation.feature_attendance.db.RouteDbHelper
import com.vigil.android.presentation.feature_attendance.db.ShiftEligibility
import com.vigil.android.presentation.feature_attendance.service.PatrolForegroundService
import com.vigil.android.presentation.feature_attendance.service.notification.PatrolNotifications
import java.io.File
import kotlin.text.get

class CurrentRouteFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var dbHelper: RouteDbHelper
    private var activeRouteId: Int? = null
    private var stopAdapter: ArrayAdapter<Map<String, String>>? = null
    private var displayListRef = mutableListOf<Map<String, String>>()

    private var rootListView: ListView? = null

    private val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                rootListView?.let { list -> renderList(list) }
            }
        }

    private var pendingVoiceRouteId: Int? = null

    private val speechToTextLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val spokenTextList = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spokenText = spokenTextList?.firstOrNull()?.trim()
                if (!spokenText.isNullOrEmpty()) {
                    val routeId = pendingVoiceRouteId
                    if (routeId != null) {
                        val existing = routeSummaries[routeId]?.trim().orEmpty()
                        val newRemark = if (existing.isNotEmpty()) "$existing $spokenText" else spokenText
                        routeSummaries[routeId] = newRemark
                        rootListView?.let { renderList(it) }
                    }
                }
            }
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted) {
            Toast.makeText(requireContext(), "Permission granted. Tap Start again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PatrolForegroundService.ACTION_ROUTE_DATA_CHANGED -> {
                    val isSpoofDetected = intent.getBooleanExtra(
                        PatrolForegroundService.EXTRA_SPOOF_DETECTED, false
                    )
                    val spoofReason = intent.getStringExtra(
                        PatrolForegroundService.EXTRA_SPOOF_REASON
                    ).orEmpty()

                    if (isSpoofDetected && isAdded) {
                        val msg = if (spoofReason.isNotEmpty()) "⚠️ GPS Spoofing Detected: $spoofReason" else "⚠️ GPS Spoofing Detected! Patrol actions disabled."
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }

                    rootListView?.let { renderList(it) }
                }

                PatrolForegroundService.ACTION_ROUTE_API_RESULT -> {
                    val success = intent.getBooleanExtra(
                        PatrolForegroundService.EXTRA_RESULT_SUCCESS, false
                    )
                    val message = intent.getStringExtra(
                        PatrolForegroundService.EXTRA_RESULT_MESSAGE
                    ).orEmpty()

                    if (message == "PENDING_STOPS_CONFIRM_REQUIRED") {
                        val routeId = pendingCompleteRouteId
                        val officerRouteJoinId = pendingCompleteOfficerRouteJoinId
                        if (routeId != null && officerRouteJoinId != null) {
                            showPendingStopsConfirmDialog(routeId, officerRouteJoinId)
                        }
                        return
                    }

                    if (message.isNotBlank() && isAdded) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    private var pendingCompleteRouteId: Int? = null
    private var pendingCompleteOfficerRouteJoinId: Int? = null
    private var pendingCompleteRouteSummary: String? = null
    private val routeSummaries = mutableMapOf<Int, String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_current_route, container, false)

        val listView = view.findViewById<ListView>(R.id.listStops)
        val emptyRouteLayout = view.findViewById<View>(R.id.emptyRouteLayout)
        listView.emptyView = emptyRouteLayout
        rootListView = listView

        prefs = Prefs(requireContext())
        dbHelper = RouteDbHelper(requireContext())
        activeRouteId = prefs.getActiveRouteId()

        renderList(listView)

        if (hasLocationPermissions()) {
            PatrolForegroundService.refreshRoutes(requireContext())
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(PatrolForegroundService.ACTION_ROUTE_DATA_CHANGED)
            addAction(PatrolForegroundService.ACTION_ROUTE_API_RESULT)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(dataChangedReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(dataChangedReceiver)
    }

    override fun onResume() {
        super.onResume()
        PatrolForegroundService.notifyAppForeground(requireContext())
        rootListView?.let { renderList(it) }
    }

    override fun onPause() {
        super.onPause()
        PatrolForegroundService.notifyAppBackground(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootListView = null
    }


    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStartRoute(routeId: Int, officerRouteJoinId: Int) {
        val alreadyActiveRouteId = prefs.getActiveRouteId()

        if (alreadyActiveRouteId != null && alreadyActiveRouteId != routeId) {
            Toast.makeText(requireContext(), "Complete current route first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasLocationPermissions()) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
            return
        }

        PatrolForegroundService.startPatrol(requireContext())
        PatrolForegroundService.startRoute(requireContext(), routeId, officerRouteJoinId)

        Toast.makeText(requireContext(), "Starting route...", Toast.LENGTH_SHORT).show()
    }

    private fun requestCompleteRoute(routeId: Int, officerRouteJoinId: Int, routeSummary: String) {
        pendingCompleteRouteId = routeId
        pendingCompleteOfficerRouteJoinId = officerRouteJoinId
        pendingCompleteRouteSummary = routeSummary
        PatrolForegroundService.completeRoute(
            requireContext(),
            routeId,
            officerRouteJoinId,
            force = false,
            routeSummary = routeSummary
        )
    }

    private fun showPendingStopsConfirmDialog(routeId: Int, officerRouteJoinId: Int) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Incomplete Stops")
            .setMessage("Some stops are not completed. Complete this route anyway?")
            .setPositiveButton("Yes") { _, _ ->
                PatrolForegroundService.completeRoute(
                    requireContext(),
                    routeId,
                    officerRouteJoinId,
                    force = true,
                    routeSummary = pendingCompleteRouteSummary.orEmpty()
                )
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun validateRouteSummary(input: String): Pair<Boolean, String> {
        val clean = input.trim().replace(Regex("""\s+"""), " ")

        if (clean.length < 3) {
            return Pair(false, "Route summary remark is mandatory. Please enter a meaningful remark.")
        }

        if (Regex("""(.)\1{4,}""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return Pair(false, "Invalid remark: Please avoid repeating characters (e.g. 'AAAAA').")
        }

        if (Regex("""\d{7,}""").containsMatchIn(clean)) {
            return Pair(false, "Invalid remark: Please enter a text summary instead of continuous numbers.")
        }

        val letterCount = clean.count { it.isLetter() }
        if (letterCount < 3) {
            return Pair(false, "Invalid remark: Please enter meaningful words with letters.")
        }

        // 4. Ensure letter count is at least equal to or greater than digit count
        val digitCount = clean.count { it.isDigit() }
        if (digitCount > letterCount && clean.length > 5) {
            return Pair(false, "Invalid remark: Remark contains too many numbers. Please write a text summary.")
        }

        return Pair(true, clean)
    }

    fun refreshRoutes() {
        PatrolForegroundService.refreshRoutes(requireContext())
    }


    private fun renderList(listView: ListView) {
        if (!isAdded) return
        val index = listView.firstVisiblePosition
        val top = listView.getChildAt(0)?.top ?: 0
        val stops = dbHelper.getAllStopsFull()
        activeRouteId = prefs.getActiveRouteId()

        val grouped = stops.groupBy { "${it["officer_route_join_id"]}_${it["route_id"]}" }
        displayListRef.clear()

        for (entry in grouped) {
            val stopsList = entry.value
            val routeId = stopsList.firstOrNull()?.get("route_id") ?: ""
            val officerRouteJoinId = stopsList.firstOrNull()?.get("officer_route_join_id") ?: "0"
            val routeName = stopsList.firstOrNull()?.get("route_name") ?: ""
            val dutyShift = stopsList.firstOrNull()?.get("duty_shift") ?: ""
            val dutyStart = stopsList.firstOrNull()?.get("duty_start") ?: ""

            val stopsListSorted = stopsList.sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }
            val lastStopId = stopsListSorted.lastOrNull()?.get("officer_stop_id") ?: ""

            val allStopsDone = stopsListSorted.all { s ->
                val id = s["officer_stop_id"] ?: ""
                val verified = !s["upload_time"].isNullOrEmpty()
                val exited = !s["exit_time"].isNullOrEmpty()

                if (id != lastStopId) {
                    verified && exited
                } else {
                    verified
                }
            }

            displayListRef.add(
                mapOf(
                    "type" to "header",
                    "route_name" to routeName,
                    "route_id" to routeId,
                    "officer_route_join_id" to officerRouteJoinId,
                    "duty_shift" to dutyShift,
                    "duty_start" to dutyStart,
                    "all_stops_done" to allStopsDone.toString()
                )
            )
            for (s in stopsList.sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }) {
                displayListRef.add(s + mapOf("type" to "stop"))
            }
        }

        if (stopAdapter == null) {
            stopAdapter = object : ArrayAdapter<Map<String, String>>(
                requireContext(),
                R.layout.item_stop,
                displayListRef
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = convertView ?: LayoutInflater.from(context)
                        .inflate(R.layout.item_stop, parent, false)

                    val item = displayListRef[position]
                    val tvName = view.findViewById<TextView>(R.id.tvName)
                    val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
                    val tvFrequency = view.findViewById<TextView>(R.id.tvFrequency)
                    val btnMap = view.findViewById<TextView>(R.id.btnMap)

                    val btnUpload = view.findViewById<Button>(R.id.btnUpload)
                    val btnEvidence = view.findViewById<TextView>(R.id.btnEvidence)
                    val layoutRouteSummaryContainer = view.findViewById<LinearLayout>(R.id.layoutRouteSummaryContainer)
                    val etRouteSummary = view.findViewById<EditText>(R.id.etRouteSummary)
                    val btnMic = view.findViewById<ImageView>(R.id.btnMic)
                    val isTracking = prefs.isTracking()

                    if (item["type"] == "header") {
                        val routeName = item["route_name"] ?: ""
                        val routeId = item["route_id"]?.toIntOrNull()
                        val officerRouteJoinId = item["officer_route_join_id"]?.toIntOrNull() ?: 0
                        val dutyShift = item["duty_shift"]
                        val dutyStart = item["duty_start"]
                        val allStopsDone = item["all_stops_done"] == "true"

                        if (activeRouteId == null && prefs.isTracking()) {
                            activeRouteId = prefs.getActiveRouteId()
                        }
                        tvName.text = "📍 $routeName"
                        tvName.textSize = 16f

                        tvStatus.visibility = View.GONE
                        tvFrequency.visibility = View.GONE
                        btnMap.visibility = View.GONE
                        btnEvidence.visibility = View.GONE

                        val isCompletable = activeRouteId == routeId || allStopsDone
                        if (isCompletable && routeId != null) {
                            layoutRouteSummaryContainer.visibility = View.VISIBLE
                            val existingSummary = routeSummaries[routeId] ?: ""
                            if (etRouteSummary.text.toString() != existingSummary) {
                                etRouteSummary.setText(existingSummary)
                            }
                            etRouteSummary.addTextChangedListener(object : android.text.TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: android.text.Editable?) {
                                    routeSummaries[routeId] = s?.toString().orEmpty()
                                }
                            })

                            btnMic?.setOnClickListener {
                                pendingVoiceRouteId = routeId

                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak route summary remark...")
                                }

                                try {
                                    speechToTextLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        requireContext(),
                                        "Speech recognition is not supported on this device.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else {
                            layoutRouteSummaryContainer.visibility = View.GONE
                        }

                        btnUpload.visibility = View.VISIBLE
                        val secureNow = SecureTime.now()
                        val isAnotherRouteLocking = activeRouteId != null && activeRouteId != routeId
                        val shiftEligibleNow = secureNow != null &&
                                ShiftEligibility.isEligibleNow(dutyShift, dutyStart, secureNow)
                        val isTimeUnverifiable = activeRouteId == null && secureNow == null
                        val isShiftLocked = activeRouteId == null && secureNow != null && !shiftEligibleNow

                        btnUpload.text = when {
                            activeRouteId == routeId -> "Complete"
                            allStopsDone -> "Complete"
                            isTimeUnverifiable -> "Locked"
                            isShiftLocked -> "Locked"
                            isAnotherRouteLocking -> "Locked"
                            else -> "Start"
                        }
                        btnUpload.setTextColor(resources.getColor(R.color.white))

                        btnUpload.setBackgroundColor(
                            when {
                                activeRouteId == routeId || allStopsDone -> ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                                isTimeUnverifiable || isShiftLocked || isAnotherRouteLocking ->
                                    ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                                else -> ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                            }
                        )
                        btnUpload.isEnabled =
                            (activeRouteId == null && shiftEligibleNow) || activeRouteId == routeId || allStopsDone

                        if (isTimeUnverifiable) {
                            tvStatus.visibility = View.VISIBLE
                            tvStatus.text = "Time Unverify - Refresh"
                        } else if (isShiftLocked) {
                            tvStatus.visibility = View.VISIBLE
                            tvStatus.text = ShiftEligibility.ineligibleReason(dutyShift, dutyStart, secureNow!!)
                        }

                        btnUpload.setOnClickListener {
                            if (routeId == null) return@setOnClickListener

                            if (activeRouteId == routeId || allStopsDone) {
                                val rawSummary = etRouteSummary.text?.toString()
                                    .takeIf { !it.isNullOrEmpty() }
                                    ?: routeSummaries[routeId].orEmpty()

                                val (isValid, validatedSummaryOrError) = validateRouteSummary(rawSummary)

                                if (!isValid) {
                                    Toast.makeText(
                                        requireContext(),
                                        validatedSummaryOrError,
                                        Toast.LENGTH_LONG
                                    ).show()
                                    etRouteSummary.requestFocus()
                                    return@setOnClickListener
                                }

                                val cleanSummary = validatedSummaryOrError
                                etRouteSummary.setText(cleanSummary)
                                routeSummaries[routeId] = cleanSummary
                                requestCompleteRoute(routeId, officerRouteJoinId, cleanSummary)
                            } else if (isTimeUnverifiable) {
                                Toast.makeText(
                                    requireContext(),
                                    "Connect to the network once to verify shift timing",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (isShiftLocked) {
                                Toast.makeText(
                                    requireContext(),
                                    ShiftEligibility.ineligibleReason(dutyShift, dutyStart, secureNow!!),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                requestStartRoute(routeId, officerRouteJoinId)
                            }
                        }

                        return view
                    }

                    layoutRouteSummaryContainer.visibility = View.GONE

                    btnMap.visibility = View.VISIBLE
                    btnUpload.visibility = View.VISIBLE
                    btnEvidence.visibility = View.VISIBLE
                    tvStatus.visibility = View.VISIBLE

                    val stopId = item["stop_id"]!!.toInt()
                    val routeId = item["route_id"]!!.toInt()
                    val officerRouteJoinId = item["officer_route_join_id"]?.toIntOrNull() ?: 0
                    val officerStopId = item["officer_stop_id"]?.toIntOrNull() ?: 0
                    val stopHash = item["stop_hash"] ?: ""
                    val lat = item["latitude"]?.toDoubleOrNull() ?: 0.0
                    val lon = item["longitude"]?.toDoubleOrNull() ?: 0.0
                    val status = item["status"]?.toIntOrNull() ?: 0
                    val reached = status >= 1 || !item["reached_time"].isNullOrEmpty()
                    val verified = status >= 2 || !item["upload_time"].isNullOrEmpty()
                    val exited = !item["exit_time"].isNullOrEmpty()
                    val freq = item["frequency_duration"]?.toIntOrNull() ?: 0

                    tvName.text = "${(item["stop_order"]?.toIntOrNull() ?: 0) + 1}. ${item["stop_name"]}"
                    tvStatus.text = when {
                        verified && exited -> "✅ Verified / Exited"
                        verified -> "✅ Verified"
                        reached -> "📍 Reached"
                        else -> "❌ Pending"
                    }

                    if (freq > 0) {
                        tvFrequency.visibility = View.VISIBLE
                        tvFrequency.text = "enable - ${freq}min"
                    } else {
                        tvFrequency.visibility = View.GONE
                    }

                    val loc = prefs.getLastKnownLocation()
                    val radius = getGeofenceRadius()
                    val isInside = if (loc != null) {
                        isInsideGeofence(loc.first, loc.second, lat, lon, radius)
                    } else {
                        false
                    }
                    var frequencyAllowed = true
                    val lastExit = dbHelper.getLastExitByHash(stopHash)
                    if (lastExit != null && freq > 0 && verified) {
                        val diff = (System.currentTimeMillis() - lastExit) / (1000 * 60)
                        if (diff < freq) {
                            frequencyAllowed = false
                        }
                    }

                    val allowVerify = isTracking && reached && isInside && !verified && frequencyAllowed
                    val allowEvidence = isTracking && reached
                    btnMap.isEnabled = true
                    btnUpload.isEnabled = allowVerify
                    btnEvidence.isEnabled = allowEvidence
                    btnEvidence.alpha = if (allowEvidence) 1f else 0.45f
                    btnUpload.text = when {
                        verified -> "VERIFIED"
                        reached -> "VERIFY"
                        else -> "VERIFY"
                    }

                    btnUpload.setTextColor(resources.getColor(R.color.white))

                    if (verified) {
                        btnUpload.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                    } else if (reached) {
                        btnUpload.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
                    } else {
                        btnUpload.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    }

                    btnMap.setOnClickListener {
                        val uri = Uri.parse("google.navigation:q=$lat,$lon")
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }

                    btnUpload.setOnClickListener {
                        val freshStop = dbHelper.getStopByOfficerStopId(officerRouteJoinId, officerStopId)
                        if (!freshStop?.get("upload_time").isNullOrEmpty()) {
                            Toast.makeText(context, "Stop already verified", Toast.LENGTH_SHORT).show()
                            rootListView?.let { renderList(it) }
                            return@setOnClickListener
                        }
                        if (!reached) {
                            Toast.makeText(context, "Reach location first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!isInside) {
                            Toast.makeText(context, "You are outside geofence", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!frequencyAllowed) {
                            val diff = if (lastExit != null) (System.currentTimeMillis() - lastExit) / (1000 * 60) else 0
                            Toast.makeText(context, "Wait ${freq - diff} min", Toast.LENGTH_SHORT).show()
                            PatrolNotifications.notifyFrequencyCooldown(requireContext(), item["stop_name"] ?: "", stopId, freq - diff)
                            return@setOnClickListener
                        }

                        val intent = Intent(context, UploadActivity::class.java)
                        intent.putExtra("stop_id", stopId)
                        intent.putExtra("route_id", routeId)
                        intent.putExtra("officer_route_join_id", officerRouteJoinId)
                        intent.putExtra("officer_stop_id", officerStopId)
                        intent.putExtra("stop_hash", stopHash)
                        intent.putExtra("lat", lat)
                        intent.putExtra("lon", lon)
                        uploadLauncher.launch(intent)
                    }

                    btnEvidence.setOnClickListener {
                        if (!isTracking) {
                            Toast.makeText(context, "Start patrol first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!reached) {
                            Toast.makeText(context, "Reach location first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        val intent = Intent(context, EvidenceUploadActivity::class.java)
                        intent.putExtra("stop_id", stopId)
                        intent.putExtra("route_id", routeId)
                        intent.putExtra("officer_route_join_id", officerRouteJoinId)
                        intent.putExtra("officer_stop_id", officerStopId)
                        intent.putExtra("officer_id", prefs.getPatrolId()?.toInt())
                        intent.putExtra("lat", lat)
                        intent.putExtra("lon", lon)
                        uploadLauncher.launch(intent)
                    }

                    view.setOnClickListener {
                        if (!isTracking) {
                            Toast.makeText(context, "Please start patrol first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!reached) {
                            Toast.makeText(context, "Reach location first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!verified) {
                            Toast.makeText(context, "Please verify first", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        showPopup(officerRouteJoinId, officerStopId)
                    }

                    return view
                }
            }
            listView.adapter = stopAdapter
            listView.setSelectionFromTop(index, top)
        } else {
            stopAdapter?.notifyDataSetChanged()
        }
    }

    private fun isInsideGeofence(
        currentLat: Double,
        currentLon: Double,
        stopLat: Double,
        stopLon: Double,
        radius: Float
    ): Boolean {
        val result = FloatArray(1)
        Location.distanceBetween(currentLat, currentLon, stopLat, stopLon, result)
        return result[0] <= radius
    }

    private fun getGeofenceRadius(): Float {
        val raw = prefs.getRadius()?.trim().orEmpty()
        val cleaned = raw
            .replace("meter", "", ignoreCase = true)
            .replace("m", "", ignoreCase = true)
            .trim()
        return cleaned.toFloatOrNull() ?: 30f
    }

    private fun showPopup(officerRouteJoinId: Int, officerStopId: Int) {
        val data = dbHelper.getStopByOfficerStopId(officerRouteJoinId, officerStopId) ?: return

        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_stop_detail)

        val tvInfo = dialog.findViewById<TextView>(R.id.tvInfo)
        val btnMap = dialog.findViewById<Button>(R.id.btnMap)
        val imageView = dialog.findViewById<ImageView>(R.id.imageView)
        val container = dialog.findViewById<LinearLayout>(R.id.imageContainer)

        val lat = data["user_latitude"]
        val lon = data["user_longitude"]

        tvInfo.text = """
Time: ${data["upload_time"]}
Lat: $lat
Lon: $lon
""".trimIndent()

        btnMap.setOnClickListener {
            val uri = Uri.parse("google.navigation:q=$lat,$lon")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }

        val imageBase64 = data["image_path"]
        if (!imageBase64.isNullOrEmpty()) {
            val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            imageView.setImageBitmap(bitmap)
            imageView.visibility = View.VISIBLE
        }

        container.removeAllViews()
        val images = dbHelper.getStopImages(officerRouteJoinId, officerStopId)

        for ((index, imageWithDesc) in images.withIndex()) {
            val img = imageWithDesc.first
            val desc = imageWithDesc.second
            val bytes = Base64.decode(img, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = resources.getDrawable(R.drawable.bg_card, null)
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 14)
                }
            }

            val iv = ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(120, 90).apply {
                    setMargins(0, 0, 14, 0)
                }
            }

            val title = TextView(requireContext()).apply {
                text = "evidence_${index + 1}.jpg"
                textSize = 16f
                setTextColor(resources.getColor(R.color.black))
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val btnShare = TextView(requireContext()).apply {
                text = "↗"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#0D47A1"))
                layoutParams = LinearLayout.LayoutParams(56, 56)
            }

            row.addView(iv)
            row.addView(title)
            row.addView(btnShare)

            row.setOnClickListener {
                showImagePreview(bitmap, desc)
            }

            btnShare.setOnClickListener {
                shareBitmap(bitmap, "evidence_${index + 1}.jpg")
            }

            container.addView(row)
        }

        dialog.show()
    }

    private fun showImagePreview(bitmap: Bitmap, description: String = "") {
        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(12, 12, 12, 12)
        }

        root.addView(imageView)

        if (description.isNotEmpty()) {
            val tvDesc = TextView(requireContext()).apply {
                text = "📝  $description"
                textSize = 14f
                setTextColor(Color.parseColor("#C9D1D9"))
                setPadding(24, 8, 24, 20)
                setBackgroundColor(Color.parseColor("#161B22"))
            }
            root.addView(tvDesc)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun shareBitmap(bitmap: Bitmap, fileName: String) {
        val file = File(requireContext().cacheDir, fileName)

        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share Evidence Image"))
    }
}