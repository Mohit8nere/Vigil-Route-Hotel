package com.vigil.android.presentation.feature_attendance.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone


object ShiftEligibility {

    data class Window(val startHour: Int, val endHour: Int, val label: String)

    private val SHIFT_WINDOWS = mapOf(
        "morning" to Window(6, 14, "Morning"),
        "day" to Window(14, 22, "Day"),
        "afternoon" to Window(14, 22, "Day"), // tolerate alternate naming
        "night" to Window(22, 6, "Night")
    )

    private fun hourInWindow(nowHour: Int, window: Window): Boolean {
        return if (window.startHour < window.endHour) {
            nowHour in window.startHour until window.endHour
        } else {
            nowHour >= window.startHour || nowHour < window.endHour
        }
    }
    private fun parseHour(dutyStart: String?): Int? {
        val s = dutyStart?.trim().orEmpty()
        if (s.isEmpty()) return null

        // ISO-8601 UTC: "2026-06-29T10:59:44Z"
        val isoRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z$""")
        isoRegex.find(s)?.let { m ->
            return try {
                val (y, mo, d, h, mi, se) = m.destructured
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), se.toInt())
                val utcMillis = cal.timeInMillis

                val localCal = Calendar.getInstance() // device-local timezone
                localCal.timeInMillis = utcMillis
                localCal.get(Calendar.HOUR_OF_DAY)
            } catch (e: Exception) {
                null
            }
        }

        // Clock string: "06:00:00 AM", "14:00:00 PM", "22:00:00 PM", or bare "10:59:44"
        val clockRegex = Regex("""^(\d{1,2}):(\d{2}):(\d{2})\s*(AM|PM)?$""", RegexOption.IGNORE_CASE)
        clockRegex.find(s)?.let { m ->
            return try {
                var hour = m.groupValues[1].toInt()
                val suffix = m.groupValues[4].uppercase()

                // Only apply AM/PM adjustment for a genuine 12-hour value.
                // Some backend payloads send 24h hours (14, 22) with a
                // redundant/incorrect AM/PM suffix still attached — in
                // that case the hour is already correct as-is.
                if (hour in 1..12 && suffix.isNotEmpty()) {
                    if (suffix == "PM" && hour != 12) hour += 12
                    if (suffix == "AM" && hour == 12) hour = 0
                }
                hour % 24
            } catch (e: Exception) {
                null
            }
        }

        return null
    }

    fun resolveWindow(dutyShift: String?, dutyStart: String?): Window? {
        val shiftKey = dutyShift?.trim()?.lowercase()
        SHIFT_WINDOWS[shiftKey]?.let { return it }

        // duty_shift missing/unrecognized — fall back to inferring the
        // window from duty_start's hour by finding which window contains it.
        val hour = parseHour(dutyStart) ?: return null
        return SHIFT_WINDOWS.values.firstOrNull { hourInWindow(hour, it) }
    }

    fun isEligibleNow(dutyShift: String?, dutyStart: String?, nowMillis: Long): Boolean {
        val window = resolveWindow(dutyShift, dutyStart) ?: return true
        val nowHour = hourOfDay(nowMillis)
        return hourInWindow(nowHour, window)
    }

    fun ineligibleReason(dutyShift: String?, dutyStart: String?, nowMillis: Long): String {
        val window = resolveWindow(dutyShift, dutyStart) ?: return "Shift time unavailable"
        val nowHour = hourOfDay(nowMillis)

        val startLabel = formatHour(window.startHour)
        val endLabel = formatHour(window.endHour)

        // Are we BEFORE this window today, or AFTER it (already past)?
        val isBefore = if (window.startHour < window.endHour) {
            nowHour < window.startHour
        } else {
            // wrapping window (night): "before" means daytime hours
            // strictly between end and start
            nowHour in window.endHour until window.startHour
        }

        return if (isBefore) {
            "Available from $startLabel"
        } else {
            "${window.label} shift ($startLabel - $endLabel) has ended"
        }
    }

    private fun hourOfDay(millis: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun formatHour(hour: Int): String {
        val h = hour % 24
        val suffix = if (h < 12) "AM" else "PM"
        val display = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$display:00 $suffix"
    }
}

class RouteDbHelper(context: Context) :
    SQLiteOpenHelper(context, "route_db", null, 12) {

    companion object {
        private const val TAG = "RouteDbHelper"
    }

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
    CREATE TABLE stops (
        officer_route_join_id INTEGER NOT NULL,
        officer_stop_id INTEGER NOT NULL,

        stop_id INTEGER,
        officer_id INTEGER,
        route_id INTEGER,
        route_name TEXT,
        stop_name TEXT,
        stop_order INTEGER,
        stop_hash TEXT,
        latitude TEXT,
        longitude TEXT,
        status INTEGER DEFAULT 0,
        frequency_duration INTEGER DEFAULT 0,

        duty_shift TEXT,
        duty_start TEXT,
        duty_date TEXT,

        image_path TEXT,
        user_latitude TEXT,
        user_longitude TEXT,
        upload_time TEXT,
        reached_time TEXT,
        exit_time TEXT,
        
        verify_latitude TEXT,
        verify_longitude TEXT,

        PRIMARY KEY (officer_route_join_id, officer_stop_id)
    )
    """.trimIndent()
        )

        db.execSQL(
            """
    CREATE TABLE stop_images (
        id INTEGER PRIMARY KEY AUTOINCREMENT,

        officer_route_join_id INTEGER NOT NULL,
        officer_stop_id INTEGER NOT NULL,

        officer_id INTEGER,
        route_id INTEGER,
        stop_id INTEGER,
        image_base64 TEXT,
        description TEXT,
        created_at TEXT
    )
    """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE meta (
                mkey TEXT PRIMARY KEY,
                value TEXT
            )
            """.trimIndent()
        )

        createSyncQueueTable(db)
    }

    fun removeObsoleteRoutes(
        latestRouteIds: Set<Int>,
        activeRouteId: Int?
    ) {
        val db = writableDatabase

        val cursor = db.rawQuery(
            "SELECT DISTINCT route_id FROM stops",
            null
        )

        cursor.use {

            while (it.moveToNext()) {

                val routeId = it.getInt(0)

                // still exists
                if (latestRouteIds.contains(routeId))
                    continue

                // active patrol
                if (activeRouteId != null &&
                    activeRouteId == routeId)
                    continue

                // unfinished route
                val pending = hasPendingRoute(routeId)

                if (pending)
                    continue

                Log.d(
                    "RouteDb",
                    "Deleting obsolete route $routeId"
                )

                db.delete(
                    "stop_images",
                    "route_id=?",
                    arrayOf(routeId.toString())
                )

                db.delete(
                    "stops",
                    "route_id=?",
                    arrayOf(routeId.toString())
                )
            }
        }
    }
    // 🔥 ADD THIS FUNCTION TO RouteDbHelper CLASS (after upsertStops function)


    fun areAllStopsVerifiedByAttempt(officerRouteJoinId: Int): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stops WHERE officer_route_join_id=? AND (upload_time IS NULL OR upload_time='')",
            arrayOf(officerRouteJoinId.toString())
        )
        var count = 0
        if (cursor.moveToFirst()) count = cursor.getInt(0)
        cursor.close()
        return count == 0
    }

    fun getUnexitedVerifiedStops(officerRouteJoinId: Int): List<Map<String, String>> {
        return getAllStopsFull().filter {
            it["officer_route_join_id"]?.toIntOrNull() == officerRouteJoinId &&
                    !it["upload_time"].isNullOrEmpty() &&
                    it["exit_time"].isNullOrEmpty()
        }
    }
    private fun hasPendingRoute(routeId: Int): Boolean {

        val cursor = readableDatabase.rawQuery(
            """
        SELECT COUNT(*)
        FROM stops
        WHERE route_id=?
        AND (
            upload_time IS NULL
            OR upload_time=''
            OR exit_time IS NULL
            OR exit_time=''
        )
        """.trimIndent(),
            arrayOf(routeId.toString())
        )

        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0) > 0
            }
        }

        return false
    }

    /**
     * ✅ OFFLINE SUPPORT: durable outbox queue.
     *
     * Every server-bound action (reach / verify-upload / evidence / exit /
     * patrol-update start/complete) is written here FIRST, in strict
     * chronological order (rowid = insertion order = the real-world order
     * these things happened to the officer). The local `stops` table is
     * always updated immediately regardless of network — that's what lets
     * reach -> verify -> evidence -> exit keep working with zero signal.
     * A drain loop in the service pops the oldest row, attempts to send
     * it, and only deletes it on confirmed success — preserving order
     * across a mix of online and offline stops on the same route.
     */
    private fun createSyncQueueTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                last_attempt_at INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // ✅ FIX: the previous implementation unconditionally dropped every
        // table on ANY version bump, which would silently wipe an
        // in-progress patrol (reached/verified/exited timestamps, queued
        // offline actions, everything) the next time the app updates on a
        // device mid-shift. We now migrate additively: only add what's
        // missing for the new version, never drop existing data.
        if (oldVersion < 11) {
            createSyncQueueTable(db)
        }
        if (oldVersion < 12) {
            try {
                db.execSQL("ALTER TABLE stops ADD COLUMN duty_shift TEXT")
            } catch (e: Exception) { /* column may already exist on a fresh install */ }
            try {
                db.execSQL("ALTER TABLE stops ADD COLUMN duty_start TEXT")
            } catch (e: Exception) { }
            try {
                db.execSQL("ALTER TABLE stops ADD COLUMN duty_date TEXT")
            } catch (e: Exception) { }
        }
    }

    // ================= BASIC =================

    fun clearAllData() {
        val db = writableDatabase
        try {
            db.delete("stops", null, null)
            db.delete("stop_images", null, null)
            db.delete("sync_queue", null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing RouteDbHelper data", e)
        }
    }

    fun clearStops() {
        writableDatabase.delete("stops", null, null)
    }

    fun insertStops(routeName: String, routeId: Int, stops: List<ContentValues>) {
        val db = writableDatabase
        for (cv in stops) {
            cv.put("route_name", routeName)
            cv.put("route_id", routeId)
            db.insertWithOnConflict("stops", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun updateStatus(stopId: Int, routeId: Int, stopHash: String, status: Int) {
        val cv = ContentValues()
        cv.put("status", status)
        writableDatabase.update(
            "stops",
            cv,
            "stop_id=? AND route_id=? AND stop_hash=?",
            arrayOf(stopId.toString(), routeId.toString(), stopHash)
        )
    }

    fun updateReached(
        officerRouteJoinId: Int,
        officerStopId: Int,
        time: String
    ) {
        val cv = ContentValues()
        cv.put("reached_time", time)
        cv.put("status", 1)

        writableDatabase.update(
            "stops",
            cv,
            "officer_route_join_id=? AND officer_stop_id=?",
            arrayOf(
                officerRouteJoinId.toString(),
                officerStopId.toString()
            )
        )
    }

    fun updateExit(
        officerRouteJoinId: Int,
        officerStopId: Int,
        time: String
    ) {
        val cv = ContentValues()
        cv.put("exit_time", time)

        writableDatabase.update(
            "stops",
            cv,
            "officer_route_join_id=? AND officer_stop_id=?",
            arrayOf(
                officerRouteJoinId.toString(),
                officerStopId.toString()
            )
        )
    }
    fun isAnyStopVerifiedAndExitedWithHash(hash: String, currentStopId: Int, currentRouteId: Int): Boolean {
        val cursor = readableDatabase.rawQuery(
            """
        SELECT 1
        FROM stops
        WHERE stop_hash = ?
          AND NOT (stop_id = ? AND route_id = ?)
          AND (status = 2 OR upload_time IS NOT NULL)
          AND exit_time IS NOT NULL
        LIMIT 1
        """.trimIndent(),
            arrayOf(hash, currentStopId.toString(), currentRouteId.toString())
        )

        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    fun updateUpload(
        officerRouteJoinId: Int,
        officerStopId: Int,
        lat: Double,
        lon: Double,
        time: String,
        imageBase64: String
    ) {
        val values = ContentValues()

        values.put("upload_time", time)
        values.put("status", 2)

        values.put("user_latitude", lat.toString())
        values.put("user_longitude", lon.toString())

        values.put("verify_latitude", lat.toString())
        values.put("verify_longitude", lon.toString())

        values.put("image_path", imageBase64)

        writableDatabase.update(
            "stops",
            values,
            "officer_route_join_id=? AND officer_stop_id=?",
            arrayOf(
                officerRouteJoinId.toString(),
                officerStopId.toString()
            )
        )
    }
    fun getStopByOfficerStopId(
        officerRouteJoinId: Int,
        officerStopId: Int
    ): Map<String, String>? {
        val cursor = readableDatabase.rawQuery(
            """
        SELECT * FROM stops 
        WHERE officer_route_join_id=? AND officer_stop_id=?
        LIMIT 1
        """.trimIndent(),
            arrayOf(
                officerRouteJoinId.toString(),
                officerStopId.toString()
            )
        )

        cursor.use {
            if (!it.moveToFirst()) return null

            val map = mutableMapOf<String, String>()

            for (i in 0 until it.columnCount) {
                map[it.getColumnName(i)] = it.getString(i) ?: ""
            }

            return map
        }
    }

    fun updateUpload(
        stopId: Int,
        routeId: Int,
        stopHash: String,
        lat: Double,
        lon: Double,
        time: String,
        imageBase64: String
    ) {
        val values = ContentValues()
        values.put("upload_time", time)
        values.put("status", 2)
        values.put("user_latitude", lat.toString())
        values.put("user_longitude", lon.toString())
        values.put("image_path", imageBase64)
        writableDatabase.update(
            "stops",
            values,
            "stop_id=? AND route_id=? AND stop_hash=?",
            arrayOf(stopId.toString(), routeId.toString(), stopHash)
        )
    }

    // ================= ACTIVE STOP =================

    fun getActiveStopHash(): String? {
        val cursor = readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE mkey=?",
            arrayOf("active_stop")
        )

        if (cursor.moveToFirst()) {
            val v = cursor.getString(0)
            cursor.close()
            return v
        }

        cursor.close()
        return null
    }

    fun setActiveStopHash(hash: String) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("mkey", "active_stop")
        cv.put("value", hash)
        db.delete("meta", "mkey=?", arrayOf("active_stop"))
        db.insert("meta", null, cv)
    }

    fun clearActiveStopHash() {
        writableDatabase.delete("meta", "mkey=?", arrayOf("active_stop"))
    }

    fun getActiveStopId(): Int? {
        val cursor = readableDatabase.rawQuery(
            "SELECT value FROM meta WHERE mkey=?",
            arrayOf("active_stop_id")
        )

        if (cursor.moveToFirst()) {
            val v = cursor.getString(0)?.toIntOrNull()
            cursor.close()
            return v
        }

        cursor.close()
        return null
    }

    fun setActiveStopId(officerStopId: Int) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("mkey", "active_stop_id")
        cv.put("value", officerStopId.toString())

        db.delete("meta", "mkey=?", arrayOf("active_stop_id"))
        db.insert("meta", null, cv)
    }

    fun clearActiveStopId() {
        writableDatabase.delete("meta", "mkey=?", arrayOf("active_stop_id"))
    }

    // ================= ROUTES =================

    fun getAllRoutes(): List<Int> {
        val list = mutableListOf<Int>()
        val cursor = readableDatabase.rawQuery(
            "SELECT DISTINCT route_id FROM stops ORDER BY route_id",
            null
        )
        while (cursor.moveToNext()) {
            list.add(cursor.getInt(0))
        }
        cursor.close()
        return list
    }

    fun getStopsForRoute(routeId: Int): List<Map<String, String>> {
        return getAllStopsFull().filter { it["route_id"]?.toIntOrNull() == routeId }
            .sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }
    }

    // ================= GET =================

    fun getStopById(stopId: Int): Map<String, String>? {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM stops WHERE stop_id=? ORDER BY upload_time DESC LIMIT 1",
            arrayOf(stopId.toString())
        )

        if (cursor.moveToFirst()) {
            val map = mapOf(
                "stop_name" to cursor.getString(cursor.getColumnIndexOrThrow("stop_name")),
                "upload_time" to (cursor.getString(cursor.getColumnIndexOrThrow("upload_time")) ?: ""),
                "lat" to (cursor.getString(cursor.getColumnIndexOrThrow("user_latitude")) ?: ""),
                "lon" to (cursor.getString(cursor.getColumnIndexOrThrow("user_longitude")) ?: ""),
                "image_path" to (cursor.getString(cursor.getColumnIndexOrThrow("image_path")) ?: "")
            )
            cursor.close()
            return map
        }

        cursor.close()
        return null
    }

    fun getAllStopsFull(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM stops ORDER BY route_id, stop_order ASC",
            null
        )

        while (cursor.moveToNext()) {
            val reachedTime = cursor.getString(cursor.getColumnIndexOrThrow("reached_time")) ?: ""
            val uploadTime = cursor.getString(cursor.getColumnIndexOrThrow("upload_time")) ?: ""
            val exitTime = cursor.getString(cursor.getColumnIndexOrThrow("exit_time")) ?: ""
            val storedStatus = cursor.getInt(cursor.getColumnIndexOrThrow("status"))

            val normalizedStatus = when {
                uploadTime.isNotEmpty() -> 2
                reachedTime.isNotEmpty() -> 1
                else -> storedStatus
            }

            val map = mapOf(
                "stop_id" to cursor.getInt(cursor.getColumnIndexOrThrow("stop_id")).toString(),
                "officer_id" to cursor.getInt(cursor.getColumnIndexOrThrow("officer_id")).toString(),
                "route_id" to cursor.getInt(cursor.getColumnIndexOrThrow("route_id")).toString(),
                "route_name" to cursor.getString(cursor.getColumnIndexOrThrow("route_name")),
                "stop_name" to cursor.getString(cursor.getColumnIndexOrThrow("stop_name")),
                "stop_order" to cursor.getInt(cursor.getColumnIndexOrThrow("stop_order")).toString(),
                "latitude" to cursor.getString(cursor.getColumnIndexOrThrow("latitude")),
                "longitude" to cursor.getString(cursor.getColumnIndexOrThrow("longitude")),
                "officer_route_join_id" to cursor.getInt(cursor.getColumnIndexOrThrow("officer_route_join_id")).toString(),
                "officer_stop_id" to cursor.getInt(cursor.getColumnIndexOrThrow("officer_stop_id")).toString(),
                "status" to normalizedStatus.toString(),
                "reached_time" to reachedTime,
                "upload_time" to uploadTime,
                "exit_time" to exitTime,
                "stop_hash" to (cursor.getString(cursor.getColumnIndexOrThrow("stop_hash")) ?: ""),
                "frequency_duration" to cursor.getInt(cursor.getColumnIndexOrThrow("frequency_duration")).toString(),
                "image_path" to (cursor.getString(cursor.getColumnIndexOrThrow("image_path")) ?: ""),
                "user_latitude" to (cursor.getString(cursor.getColumnIndexOrThrow("user_latitude")) ?: ""),
                "user_longitude" to (cursor.getString(cursor.getColumnIndexOrThrow("user_longitude")) ?: ""),
                "duty_shift" to (cursor.getString(cursor.getColumnIndexOrThrow("duty_shift")) ?: ""),
                "duty_start" to (cursor.getString(cursor.getColumnIndexOrThrow("duty_start")) ?: ""),
                "duty_date" to (cursor.getString(cursor.getColumnIndexOrThrow("duty_date")) ?: "")
            )
            list.add(map)
        }

        cursor.close()
        return list
    }

    fun getAllStops(): List<Map<String, String>> = getAllStopsFull()

    // ================= IMAGES =================
    private fun getTime(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }
    fun insertStopImage(
        officerRouteJoinId: Int,
        officerStopId: Int,
        officerId: Int,
        routeId: Int,
        stopId: Int,
        base64: String,
        description: String = ""
    ) {
        val cv = ContentValues()
        cv.put("officer_route_join_id", officerRouteJoinId)
        cv.put("officer_stop_id", officerStopId)
        cv.put("officer_id", officerId)
        cv.put("route_id", routeId)
        cv.put("stop_id", stopId)
        cv.put("image_base64", base64)
        cv.put("description", description)
        cv.put("created_at", getTime())

        writableDatabase.insert("stop_images", null, cv)
    }

    fun getStopImages(
        officerRouteJoinId: Int,
        officerStopId: Int
    ): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()

        val cursor = readableDatabase.rawQuery(
            """
    SELECT image_base64, description
    FROM stop_images 
    WHERE officer_route_join_id=? AND officer_stop_id=?
    ORDER BY id ASC
    """.trimIndent(),
            arrayOf(
                officerRouteJoinId.toString(),
                officerStopId.toString()
            )
        )

        while (cursor.moveToNext()) {
            val image = cursor.getString(0) ?: ""
            val desc = cursor.getString(1) ?: ""
            list.add(Pair(image, desc))
        }

        cursor.close()
        return list
    }
    fun clearRouteAttempt(officerRouteJoinId: Int) {
        val db = writableDatabase

        db.delete(
            "stops",
            "officer_route_join_id=?",
            arrayOf(officerRouteJoinId.toString())
        )

        db.delete(
            "stop_images",
            "officer_route_join_id=?",
            arrayOf(officerRouteJoinId.toString())
        )

        clearActiveStopId()
    }


    fun getOfficerRouteJoinId(routeId: Int): Int? {
        val cursor = readableDatabase.rawQuery(
            """
        SELECT officer_route_join_id 
        FROM stops 
        WHERE route_id=? 
        LIMIT 1
        """.trimIndent(),
            arrayOf(routeId.toString())
        )

        cursor.use {
            if (it.moveToFirst()) {
                val id = it.getInt(0)
                return if (id > 0) id else null
            }
        }

        return null
    }


    // ================= FREQUENCY =================

    fun getLastExitByHash(hash: String): Long? {
        val cursor = readableDatabase.rawQuery(
            "SELECT exit_time FROM stops WHERE stop_hash = ? AND exit_time IS NOT NULL AND exit_time != '' ORDER BY exit_time DESC LIMIT 1",
            arrayOf(hash)
        )

        if (cursor.moveToFirst()) {
            val exitTime = cursor.getString(0)
            if (exitTime.isNullOrEmpty()) {
                cursor.close()
                return null
            }

            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = sdf.parse(exitTime)
                cursor.close()
                date?.time
            } catch (e: Exception) {
                cursor.close()
                null
            }
        }

        cursor.close()
        return null
    }

    fun isAnyStopCompletedWithHash(hash: String): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT 1 FROM stops WHERE stop_hash=? AND upload_time IS NOT NULL AND upload_time != '' LIMIT 1",
            arrayOf(hash)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    /**
     * ✅ FIX (root cause of "API succeeds but UI shows nothing / shows wrong stops"):
     *
     * The `stops` table primary key is (officer_route_join_id, officer_stop_id).
     * If the backend ever omits `officer_stop_id` on a stop object, the previous
     * code defaulted it to 0 via `stop.optInt("officer_stop_id", 0)` in the caller.
     * Every stop missing that field then collapsed onto the SAME row
     * (officer_route_join_id, 0), with CONFLICT_REPLACE silently overwriting
     * each previous stop. A route with 8 stops could end up with just 1 row
     * in the DB, with no error, no failed API call, and no toast — exactly
     * matching "data reaches the server fine, but the UI doesn't show it."
     *
     * This method now rejects/skips any stop with officer_stop_id <= 0 instead
     * of silently destroying sibling rows, and logs loudly so the bad payload
     * is visible in Logcat instead of disappearing.
     */
    fun upsertStops(
        routeName: String,
        routeId: Int,
        officerRouteJoinId: Int,
        stops: List<ContentValues>
    ) {
        val db = writableDatabase

        db.beginTransaction()
        try {
            for (cv in stops) {
                val officerStopId = cv.getAsInteger("officer_stop_id") ?: 0

                if (officerStopId <= 0) {
                    Log.w(
                        TAG,
                        "REJECTED_STOP routeId=$routeId officerRouteJoinId=$officerRouteJoinId " +
                                "stopId=${cv.getAsInteger("stop_id")} officer_stop_id<=0 (was=$officerStopId). " +
                                "Backend payload is missing officer_stop_id for this stop — fix the API response, " +
                                "this row would otherwise overwrite sibling stops sharing the same primary key."
                    )
                    continue
                }

                cv.put("route_name", routeName)
                cv.put("route_id", routeId)
                cv.put("officer_route_join_id", officerRouteJoinId)

                // ✅ Preserve existing local fields if they have values
                val existing = db.rawQuery(
                    """
                SELECT upload_time, reached_time, exit_time, 
                       image_path, user_latitude, user_longitude
                FROM stops 
                WHERE officer_route_join_id=? AND officer_stop_id=?
                LIMIT 1
                """.trimIndent(),
                    arrayOf(officerRouteJoinId.toString(), officerStopId.toString())
                )

                existing.use { c ->
                    if (c.moveToFirst()) {
                        val uploadTime = c.getString(0)
                        val reachedTime = c.getString(1)
                        val exitTime = c.getString(2)
                        val imagePath = c.getString(3)
                        val userLat = c.getString(4)
                        val userLon = c.getString(5)

                        if (!uploadTime.isNullOrEmpty()) cv.put("upload_time", uploadTime)
                        if (!reachedTime.isNullOrEmpty()) cv.put("reached_time", reachedTime)
                        if (!exitTime.isNullOrEmpty()) cv.put("exit_time", exitTime)
                        if (!imagePath.isNullOrEmpty()) cv.put("image_path", imagePath)
                        if (!userLat.isNullOrEmpty()) cv.put("user_latitude", userLat)
                        if (!userLon.isNullOrEmpty()) cv.put("user_longitude", userLon)
                    }
                }

                db.insertWithOnConflict(
                    "stops",
                    null,
                    cv,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAndReplaceStops(
        routeName: String,
        routeId: Int,
        officerRouteJoinId: Int,
        newStops: List<ContentValues>
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldStops = db.rawQuery(
                """
            SELECT officer_route_join_id, officer_stop_id, 
                   reached_time, upload_time, exit_time, 
                   image_path, user_latitude, user_longitude
            FROM stops 
            WHERE officer_route_join_id=? AND route_id=?
            """.trimIndent(),
                arrayOf(officerRouteJoinId.toString(), routeId.toString())
            )

            val localEdits = mutableMapOf<Int, Map<String, String>>()
            oldStops.use { c ->
                while (c.moveToNext()) {
                    val officerStopId = c.getInt(1)
                    localEdits[officerStopId] = mapOf(
                        "reached_time" to (c.getString(2) ?: ""),
                        "upload_time" to (c.getString(3) ?: ""),
                        "exit_time" to (c.getString(4) ?: ""),
                        "image_path" to (c.getString(5) ?: ""),
                        "user_latitude" to (c.getString(6) ?: ""),
                        "user_longitude" to (c.getString(7) ?: "")
                    )
                }
            }

            val deleteCount = db.delete(
                "stops",
                "officer_route_join_id=? AND route_id=?",
                arrayOf(officerRouteJoinId.toString(), routeId.toString())
            )
            Log.d(TAG, "CLEARED_STOPS_FOR_REFRESH routeId=$routeId officerRouteJoinId=$officerRouteJoinId deletedCount=$deleteCount")

            for (cv in newStops) {
                val officerStopId = cv.getAsInteger("officer_stop_id") ?: 0

                if (officerStopId <= 0) {
                    Log.w(TAG, "REJECTED_STOP routeId=$routeId officerRouteJoinId=$officerRouteJoinId stopId=${cv.getAsInteger("stop_id")} officer_stop_id<=0")
                    continue
                }

                cv.put("route_name", routeName)
                cv.put("route_id", routeId)
                cv.put("officer_route_join_id", officerRouteJoinId)

                localEdits[officerStopId]?.let { edits ->
                    if (edits["upload_time"]?.isNotEmpty() == true) cv.put("upload_time", edits["upload_time"])
                    if (edits["reached_time"]?.isNotEmpty() == true) cv.put("reached_time", edits["reached_time"])
                    if (edits["exit_time"]?.isNotEmpty() == true) cv.put("exit_time", edits["exit_time"])
                    if (edits["image_path"]?.isNotEmpty() == true) cv.put("image_path", edits["image_path"])
                    if (edits["user_latitude"]?.isNotEmpty() == true) cv.put("user_latitude", edits["user_latitude"])
                    if (edits["user_longitude"]?.isNotEmpty() == true) cv.put("user_longitude", edits["user_longitude"])
                }

                db.insertWithOnConflict("stops", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            Log.d(TAG, "REFRESHED_STOPS routeId=$routeId officerRouteJoinId=$officerRouteJoinId insertedCount=${newStops.size}")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun isAllStopsCompleted(): Boolean {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stops WHERE upload_time IS NULL OR upload_time = ''",
            null
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count == 0
    }

    fun hasCriticalPendingStops(officerRouteJoinId: Int): Boolean {
        // Get all stops for this attempt, sorted by the order they were received from backend
        val stops = getAllStopsFull().filter {
            it["officer_route_join_id"] == officerRouteJoinId.toString()
        }.sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }

        if (stops.isEmpty()) return false

        // The "last" stop is the one that was last in the backend response
        val lastStopId = stops.last()["officer_stop_id"] ?: ""

        for (s in stops) {
            val id = s["officer_stop_id"] ?: ""
            val verified = !s["upload_time"].isNullOrEmpty()
            val exited = !s["exit_time"].isNullOrEmpty()

            if (!verified) return true

            // If an intermediate stop (not the last one) is not exited, it's critical.
            if (id != lastStopId && !exited) return true
        }

        return false
    }

    fun hasPendingStopsByAttempt(officerRouteJoinId: Int): Boolean {
        val cursor = readableDatabase.rawQuery(
            """
        SELECT COUNT(*) 
        FROM stops 
        WHERE officer_route_join_id = ?
        AND (
            upload_time IS NULL OR upload_time = '' 
            OR exit_time IS NULL OR exit_time = ''
        )
        """.trimIndent(),
            arrayOf(officerRouteJoinId.toString())
        )

        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }

        cursor.close()
        return count > 0
    }
    fun clearRoute(routeId: Int) {
        writableDatabase.delete("stops", "route_id=?", arrayOf(routeId.toString()))
    }
    fun clearRouteAndResetActive(routeId: Int) {
        writableDatabase.delete("stops", "route_id=?", arrayOf(routeId.toString()))
        clearActiveStopId()
    }
    fun isPatrolCompleted(): Boolean {
        val stops = getAllStopsFull()

        // no routes / no stops
        if (stops.isEmpty()) {
            println("[PatrolDebug] PATROL_COMPLETE: no stops found")
            return true
        }

        // Group by attempt to check last stop per attempt
        val grouped = stops.groupBy { it["officer_route_join_id"] }

        for ((_, attemptStops) in grouped) {
            val sorted = attemptStops.sortedBy { it["stop_order"]?.toIntOrNull() ?: 0 }
            val lastStopId = sorted.lastOrNull()?.get("officer_stop_id") ?: ""

            for (s in sorted) {
                val id = s["officer_stop_id"] ?: ""
                val uploadTime = s["upload_time"].orEmpty()
                val exitTime = s["exit_time"].orEmpty()

                val verified = uploadTime.isNotEmpty()
                val exited = exitTime.isNotEmpty()

                if (!verified) return false
                if (id != lastStopId && !exited) return false
            }
        }

        println("[PatrolDebug] PATROL_COMPLETE: all critical work done")
        return true
    }

    // ================= OFFLINE SYNC QUEUE =================

    /**
     * One row per pending server action. action_type is one of:
     * "patrol_update_start", "patrol_update_complete", "reach", "verify_upload",
     * "evidence", "exit". payload_json holds everything that call needs to
     * replay itself later, including any officer-supplied data (image
     * base64, lat/lon, timestamps) that was captured at the moment the
     * action happened — NOT re-read at sync time, since the officer may
     * have moved on by then.
     */
    fun enqueueSyncAction(actionType: String, payloadJson: String): Long {
        val cv = ContentValues()
        cv.put("action_type", actionType)
        cv.put("payload_json", payloadJson)
        cv.put("created_at", System.currentTimeMillis())
        cv.put("retry_count", 0)
        return writableDatabase.insert("sync_queue", null, cv)
    }

    data class SyncQueueItem(
        val id: Long,
        val actionType: String,
        val payloadJson: String,
        val retryCount: Int
    )

    /**
     * Returns the SINGLE oldest queued item, or null if the queue is empty.
     * The drain loop processes exactly one at a time and only asks for the
     * next one after the current one is confirmed sent — this is what
     * guarantees strict chronological order across mixed online/offline
     * stops, instead of firing many queued requests in parallel and
     * letting network timing reorder them.
     */
    fun peekOldestSyncAction(): SyncQueueItem? {
        val cursor = readableDatabase.rawQuery(
            "SELECT id, action_type, payload_json, retry_count FROM sync_queue ORDER BY id ASC LIMIT 1",
            null
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return SyncQueueItem(
                id = it.getLong(0),
                actionType = it.getString(1),
                payloadJson = it.getString(2),
                retryCount = it.getInt(3)
            )
        }
    }

    fun deleteSyncAction(id: Long) {
        writableDatabase.delete("sync_queue", "id=?", arrayOf(id.toString()))
    }

    fun markSyncAttemptFailed(id: Long, error: String) {
        writableDatabase.execSQL(
            "UPDATE sync_queue SET retry_count = retry_count + 1, last_error = ?, last_attempt_at = ? WHERE id = ?",
            arrayOf(error, System.currentTimeMillis().toString(), id.toString())
        )
    }

    fun getSyncQueueSize(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_queue", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun hasPendingSyncActions(): Boolean = getSyncQueueSize() > 0
}