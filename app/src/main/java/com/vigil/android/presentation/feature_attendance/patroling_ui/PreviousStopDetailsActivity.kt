package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vigil.android.core.common.network.loadPatrolImage
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.vigil.android.R
import com.vigil.android.core.di.api.ApiNames.BASE_URL
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class PreviousStopDetailsActivity : AppCompatActivity() {

    private val client = OkHttpClient()

    private val outputDateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)

    private lateinit var progressBar: ProgressBar
    private lateinit var contentLayout: LinearLayout
    private lateinit var tvStopName: TextView
    private lateinit var tvRouteName: TextView
    private lateinit var tvDateTime: TextView
    private lateinit var tvOfficerName: TextView
    private lateinit var tvReachedTime: TextView
    private lateinit var tvUploadTime: TextView
    private lateinit var tvExitTime: TextView
    private lateinit var tvLatLng: TextView
    private lateinit var imgFace: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnOpenMap: TextView

    // NEW
    private lateinit var tvEvidenceTitle: TextView
    private lateinit var evidenceContainer: LinearLayout

    private var latitude = ""
    private var longitude = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_previous_stop_details)

        progressBar = findViewById(R.id.progressBar)
        contentLayout = findViewById(R.id.contentLayout)
        tvStopName = findViewById(R.id.tvStopName)
        tvRouteName = findViewById(R.id.tvRouteName)
        tvDateTime = findViewById(R.id.tvDateTime)
        tvOfficerName = findViewById(R.id.tvOfficerName)
        tvReachedTime = findViewById(R.id.tvReachedTime)
        tvUploadTime = findViewById(R.id.tvUploadTime)
        tvExitTime = findViewById(R.id.tvExitTime)
        tvLatLng = findViewById(R.id.tvLatLng)
        imgFace = findViewById(R.id.imgFace)
        btnBack = findViewById(R.id.btnBack)
        btnOpenMap = findViewById(R.id.btnOpenMap)

        // NEW
        tvEvidenceTitle = findViewById(R.id.tvEvidenceTitle)
        evidenceContainer = findViewById(R.id.evidenceContainer)

        btnBack.setOnClickListener { finish() }

        btnOpenMap.setOnClickListener {
            if (latitude.isNotEmpty() && longitude.isNotEmpty() && latitude != "null" && longitude != "null") {
                val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            }
        }

        val routeId = intent.getIntExtra("route_id", 0)
        val stopId = intent.getIntExtra("stop_id", 0)
        val officerRouteJoinId = intent.getIntExtra("officer_route_join_id", 0)
        val officerStopId = intent.getIntExtra("officer_stop_id", 0)
        val officerId = (intent.getStringExtra("officer_id") ?: "").replace(Regex("[\r\n]"), "")
        val token = (intent.getStringExtra("token") ?: "").replace(Regex("[\r\n]"), "")
        val routeName = intent.getStringExtra("route_name") ?: "-"

        tvRouteName.text = routeName

        callStopInformationApi(routeId, stopId, officerRouteJoinId, officerStopId, officerId, token)
    }

    private fun callStopInformationApi(
        routeId: Int,
        stopId: Int,
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: String,
        token: String
    ) {
        progressBar.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE

        val url = "${BASE_URL}api/v1/duties/stops-information/".toHttpUrl().newBuilder()
            .addQueryParameter("route_id", routeId.toString())
            .addQueryParameter("stop_id", stopId.toString())
            .addQueryParameter("officer_id", officerId)
            .apply {
                if (officerStopId > 0) {
                    addQueryParameter("officer_stop_id", officerStopId.toString())
                }
                if (officerRouteJoinId > 0) {
                    addQueryParameter("officer_route_join_id", officerRouteJoinId.toString())
                }
            }
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${token.replace(Regex("[\r\n]"), "")}")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    contentLayout.visibility = View.VISIBLE
                    Toast.makeText(this@PreviousStopDetailsActivity, "API failed", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string().orEmpty()

                val stopObj: JSONObject? = try {
                    val obj = JSONObject(res)
                    when {
                        (obj.optJSONArray("data")?.length() ?: 0) > 0 -> {
                            val arr = obj.optJSONArray("data")!!
                            arr.getJSONObject(arr.length() - 1)
                        }
                        obj.optJSONObject("data") != null -> obj.optJSONObject("data")
                        (obj.optJSONArray("results")?.length() ?: 0) > 0 -> {
                            val arr = obj.optJSONArray("results")!!
                            arr.getJSONObject(arr.length() - 1)
                        }
                        obj.optJSONObject("result") != null -> obj.optJSONObject("result")
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    contentLayout.visibility = View.VISIBLE

                    if (stopObj != null) {
                        tvStopName.text = safeText(stopObj.optString("stop_name"))
                        tvOfficerName.text = safeText(stopObj.optString("officer_name"))
                        tvDateTime.text = formatServerTime(stopObj.optString("route_time"))

                        tvReachedTime.text = "Reached: ${formatServerTime(stopObj.optString("reached_time"))}"
                        tvUploadTime.text = "Verified: ${formatServerTime(stopObj.optString("verified_time"))}"
                        tvExitTime.text = "Exited: ${formatServerTime(stopObj.optString("exit_time"))}"

                        latitude = stopObj.optString("latitude")
                        longitude = stopObj.optString("longitude")
                        tvLatLng.text = "Location: ${safeText(latitude)}, ${safeText(longitude)}"

                        // ✅ FACE IMAGE FIX
                        val faceImage = when {
                            stopObj.has("encoded_image") -> stopObj.optString("encoded_image")
                            stopObj.has("image") -> stopObj.optString("image")
                            stopObj.has("face_image") -> stopObj.optString("face_image")
                            else -> ""
                        }
                        setBase64Image(faceImage, imgFace)
                    }

                    // ✅ Load evidence/background images
                    callStopBackgroundImagesApi(
                        routeId = routeId,
                        stopId = stopId,
                        officerRouteJoinId = officerRouteJoinId,
                        officerId = officerId,
                        token = token
                    )
                }
            }
        })
    }

    private fun callStopBackgroundImagesApi(
        routeId: Int,
        stopId: Int,
        officerRouteJoinId: Int,
        officerId: String,
        token: String
    ) {
        val url = "${BASE_URL}api/v1/duties/stop-background-images/".toHttpUrl().newBuilder()
            .addQueryParameter("route_id", routeId.toString())
            .addQueryParameter("stop_id", stopId.toString())
            .addQueryParameter("officer_id", officerId)
            .addQueryParameter("officer_route_join_id", officerRouteJoinId.toString())
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${token.replace(Regex("[\r\n]"), "")}")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvEvidenceTitle.visibility = View.GONE
                    evidenceContainer.removeAllViews()
                    Toast.makeText(
                        this@PreviousStopDetailsActivity,
                        "Evidence images API failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string().orEmpty()
                try {
                    val obj = JSONObject(res)

                    val dataArray =
                        obj.optJSONArray("data")
                            ?: obj.optJSONArray("images")
                            ?: obj.optJSONArray("results")
                            ?: JSONArray()

                    runOnUiThread {
                        showEvidenceImages(dataArray)
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        tvEvidenceTitle.visibility = View.GONE
                        evidenceContainer.removeAllViews()
                        Toast.makeText(
                            this@PreviousStopDetailsActivity,
                            "Evidence images parsing failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    // ✅ FIXED IMAGE HANDLER
    private fun setBase64Image(base64: String, imageView: ImageView) {
        if (base64.isEmpty() || base64 == "null") {
            imageView.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            val bitmap = loadPatrolImage(this@PreviousStopDetailsActivity, base64)
            imageView.setImageBitmap(bitmap)
            imageView.visibility = if (bitmap == null) View.GONE else View.VISIBLE
        }
    }

    // ✅ EVIDENCE UI
    private fun showEvidenceImages(array: JSONArray) {
        evidenceContainer.removeAllViews()

        if (array.length() == 0) {
            tvEvidenceTitle.visibility = View.GONE
            return
        }

        tvEvidenceTitle.text = "Evidence Images (${array.length()})"
        tvEvidenceTitle.visibility = View.VISIBLE

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(12, 12, 12, 12)
            row.gravity = Gravity.CENTER_VERTICAL

            val bg = GradientDrawable()
            bg.cornerRadius = 20f
            bg.setColor(Color.WHITE)
            row.background = bg

            val img = ImageView(this)
            img.layoutParams = LinearLayout.LayoutParams(150, 100)
            img.scaleType = ImageView.ScaleType.CENTER_CROP

            val imageBase64 = when {
                obj.has("background_image") -> obj.optString("background_image")
                obj.has("image") -> obj.optString("image")
                obj.has("image_base64") -> obj.optString("image_base64")
                else -> ""
            }

            setBase64Image(imageBase64, img)

            val textBox = LinearLayout(this)
            textBox.orientation = LinearLayout.VERTICAL
            textBox.setPadding(16, 0, 0, 0)
            textBox.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val description = obj.optString("description", "")

            val tvName = TextView(this)
            tvName.text = "evidence_${i + 1}.jpg"
            tvName.setTextColor(Color.BLACK)
            tvName.textSize = 15f
            tvName.setTypeface(null, Typeface.BOLD)

            val tvDescription = TextView(this)
            tvDescription.text = if (description.isNotEmpty() && description != "null") {
                description
            } else {
                "No description"
            }
            tvDescription.setTextColor(Color.parseColor("#4B5563"))
            tvDescription.textSize = 13f
            tvDescription.setPadding(0, 4, 0, 0)

            val tvTime = TextView(this)
            tvTime.text = formatServerTime(obj.optString("created_at"))
            tvTime.setTextColor(Color.parseColor("#6B7280"))
            tvTime.textSize = 12f
            tvTime.setPadding(0, 4, 0, 0)

            textBox.addView(tvName)
            textBox.addView(tvDescription)
            textBox.addView(tvTime)

            row.addView(img)
            row.addView(textBox)

            evidenceContainer.addView(row)
        }
    }

    private fun safeText(value: String): String {
        return if (value.isEmpty() || value == "null") "-" else value
    }

    private fun formatServerTime(value: String): String {
        if (value.isEmpty() || value == "null") return "-"

        return try {
            val clean = value.replace("T", " ").replace("+00:00", "").substring(0, 19)
            val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(clean)
            outputDateTimeFormat.format(parsed!!)
        } catch (e: Exception) {
            value
        }
    }
}