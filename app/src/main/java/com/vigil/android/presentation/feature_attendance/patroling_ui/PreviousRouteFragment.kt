package com.vigil.android.presentation.feature_attendance.patroling_ui

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vigil.android.R
import com.vigil.android.core.common.network.NetworkClient
import com.vigil.android.core.common.session.SessionManager
import com.vigil.android.core.di.api.ApiNames.BASE_URL
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PreviousRouteFragment : Fragment() {


    private lateinit var prefs: Prefs
    private lateinit var rvRoutes: RecyclerView
    private lateinit var tvDateRange: TextView
    private lateinit var btnFilter: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    private val client by lazy {
        NetworkClient.create(
            context = requireContext(),
            sessionManager = SessionManager(requireContext())
        )
    }

    private val routes = mutableListOf<PreviousRoute>()

    private lateinit var adapter: PreviousRouteAdapter

    private var fromDate = ""
    private var toDate = ""

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private val inputDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputDateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_previous_route, container, false)

        prefs = Prefs(requireContext())

        rvRoutes = view.findViewById(R.id.rvPreviousRoutes)
        tvDateRange = view.findViewById(R.id.tvDateRange)
        btnFilter = view.findViewById(R.id.btnFilter)
        progressBar = view.findViewById(R.id.progressBar)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = PreviousRouteAdapter(
            routes = routes,
            onRouteClick = { selectedRoute ->
                selectedRoute.isExpanded = !selectedRoute.isExpanded
                adapter.notifyDataSetChanged()
            },
            onStopClick = { route, stop ->
                openStopDetailsScreen(route, stop)
            }
        )

        rvRoutes.layoutManager = LinearLayoutManager(requireContext())
        rvRoutes.adapter = adapter

        setDefaultDateRange()
        updateDateText()

        tvDateRange.setOnClickListener {
            openFromDatePicker()
        }

        btnFilter.setOnClickListener {
            callPreviousRouteApi()
        }

        callPreviousRouteApi()

        return view
    }

    fun refreshRoutes() {
        callPreviousRouteApi()
    }
    private fun openStopDetailsScreen(route: PreviousRoute, stop: PreviousStop) {
        val token = prefs.getToken()
        val patrolId = prefs.getPatrolId()

        if (token.isNullOrEmpty() || patrolId.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Token or officer id missing", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(requireContext(), PreviousStopDetailsActivity::class.java)
        intent.putExtra("route_id", route.routeId)
        intent.putExtra("stop_id", stop.stopId)
        intent.putExtra("officer_stop_id", stop.officerStopId)
        intent.putExtra("officer_route_join_id", route.officerRouteJoinId)
        intent.putExtra("officer_id", patrolId)
        intent.putExtra("token", token)
        intent.putExtra("route_name", route.routeName)
        startActivity(intent)
    }
    private fun setDefaultDateRange() {
        val cal = Calendar.getInstance()
        toDate = apiDateFormat.format(cal.time)

        cal.add(Calendar.DAY_OF_MONTH, -7)
        fromDate = apiDateFormat.format(cal.time)
    }

    private fun updateDateText() {
        try {
            val from = displayDateFormat.format(apiDateFormat.parse(fromDate)!!)
            val to = displayDateFormat.format(apiDateFormat.parse(toDate)!!)
            tvDateRange.text = "$from - $to"
        } catch (e: Exception) {
            tvDateRange.text = "$fromDate - $toDate"
        }
    }

    private fun openFromDatePicker() {
        val cal = Calendar.getInstance()

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = Calendar.getInstance()
                selected.set(year, month, day)
                fromDate = apiDateFormat.format(selected.time)
                openToDatePicker()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openToDatePicker() {
        val cal = Calendar.getInstance()

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = Calendar.getInstance()
                selected.set(year, month, day)
                toDate = apiDateFormat.format(selected.time)
                updateDateText()
                callPreviousRouteApi()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun callPreviousRouteApi() {
        val token = prefs.getToken()
        val patrolId = prefs.getPatrolId()

        if (token.isNullOrEmpty() || patrolId.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Token or officer id missing", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        val url = "${BASE_URL}api/v1/routes/officer-previous-route/$patrolId/?from_date=$fromDate&to_date=$toDate"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Previous route API failed", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val res = response.body?.string().orEmpty()

                try {
                    val obj = JSONObject(res)
                    val dataArray = obj.optJSONArray("data") ?: JSONArray()

                    routes.clear()

                    for (i in 0 until dataArray.length()) {
                        val routeObj = dataArray.getJSONObject(i)

                        val route = PreviousRoute(
                            routeId = routeObj.optInt("route_id"),
                            officerRouteJoinId = routeObj.optInt("officer_route_join_id", 0),
                            routeName = routeObj.optString("route_name", "Unknown Route"),
                            routeDescription = routeObj.optString("route_description", ""),
                            dutyDate = routeObj.optString("duty_date", ""),
                            routeStatus = routeObj.optInt("route_status", 0),
                            stops = mutableListOf()
                        )

                        val stopsArray = routeObj.optJSONArray("stops") ?: JSONArray()

                        for (j in 0 until stopsArray.length()) {
                            val stopObj = stopsArray.getJSONObject(j)

                            route.stops.add(
                                PreviousStop(
                                    officerStopId = stopObj.optInt("officer_stop_id"),
                                    stopId = stopObj.optInt("stop_id"),
                                    stopName = stopObj.optString("stop_name", "Unknown Stop"),
                                    stopOrder = j + 1,
                                    stopHash = stopObj.optString("stop_hash", ""),
                                    status = stopObj.optInt("status", 0),
                                    latitude = stopObj.optString("latitude", ""),
                                    longitude = stopObj.optString("longitude", ""),
                                    frequencyDuration = stopObj.optInt("frequency_duration", 0)
                                )
                            )
                        }

                        routes.add(route)
                    }

                    activity?.runOnUiThread {
                        progressBar.visibility = View.GONE
                        adapter.notifyDataSetChanged()

                        if (routes.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                        } else {
                            tvEmpty.visibility = View.GONE
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    activity?.runOnUiThread {
                        progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), "Previous route parsing failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }






    data class PreviousRoute(
        val routeId: Int,
        val officerRouteJoinId: Int,
        val routeName: String,
        val routeDescription: String,
        val dutyDate: String,
        val routeStatus: Int,
        val stops: MutableList<PreviousStop>,
        var isExpanded: Boolean = false
    )

    data class PreviousStop(
        val officerStopId: Int,
        val stopId: Int,
        val stopName: String,
        val stopOrder: Int,
        val stopHash: String,
        val status: Int,
        val latitude: String,
        val longitude: String,
        val frequencyDuration: Int
    )



    inner class PreviousRouteAdapter(
        private val routes: MutableList<PreviousRoute>,
        private val onRouteClick: (PreviousRoute) -> Unit,
        private val onStopClick: (PreviousRoute, PreviousStop) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_ROUTE = 1
        private val TYPE_STOP = 2

        private val displayItems = mutableListOf<Any>()

        override fun getItemViewType(position: Int): Int {
            return if (displayItems[position] is PreviousRoute) TYPE_ROUTE else TYPE_STOP
        }

        override fun getItemCount(): Int {
            buildDisplayItems()
            return displayItems.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_ROUTE) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_previous_route, parent, false)
                RouteViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_previous_stop, parent, false)
                StopViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = displayItems[position]

            if (holder is RouteViewHolder && item is PreviousRoute) {
                holder.bind(item)
            } else if (holder is StopViewHolder && item is StopDisplayItem) {
                holder.bind(item.route, item.stop)
            }
        }

        private fun buildDisplayItems() {
            displayItems.clear()

            for (route in routes) {
                displayItems.add(route)

                if (route.isExpanded) {
                    route.stops.forEach { stop ->
                        displayItems.add(StopDisplayItem(route, stop))
                    }
                }
            }
        }

        inner class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvRouteDate: TextView = itemView.findViewById(R.id.tvRouteDate)
            private val tvRouteName: TextView = itemView.findViewById(R.id.tvRouteName)
            private val tvStopCount: TextView = itemView.findViewById(R.id.tvStopCount)
            private val tvArrow: ImageView = itemView.findViewById(R.id.tvArrow)

            fun bind(route: PreviousRoute) {
                tvRouteDate.text = formatDutyDate(route.dutyDate)
                tvRouteName.text = route.routeName
                tvStopCount.text = "${route.stops.size} Stops"
                if (route.isExpanded) {
                    tvArrow.setImageResource(R.drawable.ic_arrow_up)
                } else {
                    tvArrow.setImageResource(R.drawable.ic_down_arrow)
                }


                itemView.setOnClickListener {
                    onRouteClick(route)
                }
            }
        }

        inner class StopViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvStopOrder: TextView = itemView.findViewById(R.id.tvStopOrder)
            private val tvStopName: TextView = itemView.findViewById(R.id.tvStopName)
            private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
            private val btnViewDetails: TextView = itemView.findViewById(R.id.btnViewDetails)

            fun bind(route: PreviousRoute, stop: PreviousStop) {
                tvStopOrder.visibility = View.VISIBLE
                tvStopOrder.text = "${stop.stopOrder}."
                tvStopName.text = stop.stopName

                if (stop.status == 2) {
                    tvStatus.text = "✓"
                    tvStatus.setTextColor(Color.parseColor("#22C55E"))
                } else {
                    tvStatus.text = "✕"
                    tvStatus.setTextColor(Color.parseColor("#FF6B6B"))
                }

                btnViewDetails.setOnClickListener {
                    onStopClick(route, stop)
                }

                itemView.setOnClickListener {
                    onStopClick(route, stop)
                }
            }
        }

        inner class StopDisplayItem(
            val route: PreviousRoute,
            val stop: PreviousStop
        )

        private fun formatDutyDate(date: String): String {
            return try {
                val parsed = inputDateFormat.parse(date)
                displayDateFormat.format(parsed!!)
            } catch (e: Exception) {
                date
            }
        }
    }
}