package com.peretz.barkannav

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Travel-time estimates from the Google Routes API. The key is restricted to this
 * app's package and signing certificate, which are sent with every request.
 *
 * Driving times use live traffic. Public-transport times come from the operators'
 * timetables, which already reflect the buses' dedicated lanes.
 */
object Routes {
    private const val URL_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** Minutes allowed for parking and walking to the bus stop at the lot. */
    const val PARK_MINUTES = 5

    val hasKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    data class Transit(val minutes: Int, val lines: List<String>, val firstDeparture: String)

    /** One way to get there. [lot] is null for driving all the way. */
    data class Option(
        val lot: Lot?,
        val driveMinutes: Int,
        val transit: Transit?,
    ) {
        val totalMinutes: Int get() = driveMinutes + (transit?.let { PARK_MINUTES + it.minutes } ?: 0)
    }

    class RoutesError(message: String) : Exception(message)

    /** Estimates every lot and driving all the way, fastest first. Lots that fail are left out. */
    suspend fun compare(ctx: Context, fromLat: Double, fromLng: Double, destination: String, lots: List<Lot>): List<Option> =
        withContext(Dispatchers.IO) {
            val origin = latLng(fromLat, fromLng)
            val dest = JSONObject().put("address", destination)
            coroutineScope {
                val direct = async { runCatching { Option(null, drive(ctx, origin, dest), null) } }
                val viaLots = lots.map { lot ->
                    async {
                        runCatching {
                            val lotPoint = JSONObject().put("address", lot.query)
                            val driveMin = drive(ctx, origin, lotPoint)
                            val leave = Instant.now().plusSeconds((driveMin + PARK_MINUTES) * 60L)
                            Option(lot, driveMin, transit(ctx, lotPoint, dest, leave))
                        }
                    }
                }
                val all = (listOf(direct) + viaLots).awaitAll()
                val ok = all.mapNotNull { it.getOrNull() }
                if (ok.isEmpty()) {
                    throw all.firstNotNullOfOrNull { it.exceptionOrNull() } ?: RoutesError("לא נמצא מסלול")
                }
                ok.sortedBy { it.totalMinutes }
            }
        }

    private fun latLng(lat: Double, lng: Double) = JSONObject().put(
        "location", JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lng)),
    )

    private fun drive(ctx: Context, origin: JSONObject, dest: JSONObject): Int {
        val body = JSONObject()
            .put("origin", origin)
            .put("destination", dest)
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("languageCode", "he")
            .put("regionCode", "IL")
        val route = post(ctx, body, "routes.duration")
        return minutes(route.getString("duration"))
    }

    private fun transit(ctx: Context, origin: JSONObject, dest: JSONObject, leave: Instant): Transit {
        val body = JSONObject()
            .put("origin", origin)
            .put("destination", dest)
            .put("travelMode", "TRANSIT")
            .put("departureTime", leave.toString())
            .put("languageCode", "he")
            .put("regionCode", "IL")
        val route = post(
            ctx, body,
            "routes.duration,routes.legs.steps.travelMode," +
                "routes.legs.steps.transitDetails.transitLine.nameShort," +
                "routes.legs.steps.transitDetails.transitLine.name," +
                "routes.legs.steps.transitDetails.stopDetails.departureTime",
        )
        val lines = mutableListOf<String>()
        var first = ""
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (i in 0 until legs.length()) {
            val steps = legs.getJSONObject(i).optJSONArray("steps") ?: continue
            for (j in 0 until steps.length()) {
                val td = steps.getJSONObject(j).optJSONObject("transitDetails") ?: continue
                val line = td.optJSONObject("transitLine")
                val name = line?.optString("nameShort").orEmpty().ifBlank { line?.optString("name").orEmpty() }
                if (name.isNotBlank()) lines += name
                if (first.isEmpty()) first = clock(td.optJSONObject("stopDetails")?.optString("departureTime").orEmpty())
            }
        }
        return Transit(minutes(route.getString("duration")), lines, first)
    }

    private fun post(ctx: Context, body: JSONObject, fields: String): JSONObject {
        val conn = URL(URL_ROUTES).openConnection() as? HttpsURLConnection ?: throw RoutesError("HTTPS only")
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", fields)
            conn.setRequestProperty("X-Android-Package", ctx.packageName)
            conn.setRequestProperty("X-Android-Cert", certSha1(ctx))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText().take(500_000) }.orEmpty()
            if (code != HttpURLConnection.HTTP_OK) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw RoutesError("שגיאה מגוגל ($code)" + (msg?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
            }
            val routes = JSONObject(text).optJSONArray("routes")
            if (routes == null || routes.length() == 0) throw RoutesError("לא נמצא מסלול")
            return routes.getJSONObject(0)
        } finally {
            conn.disconnect()
        }
    }

    /** "1234s" to whole minutes, rounded up. */
    private fun minutes(duration: String): Int {
        val s = duration.removeSuffix("s").toDoubleOrNull() ?: throw RoutesError("זמן לא תקין")
        return kotlin.math.ceil(s / 60.0).toInt()
    }

    private fun clock(rfc3339: String): String = runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date.from(Instant.parse(rfc3339)))
    }.getOrDefault("")

    private var cert: String? = null

    /** SHA-1 of this app's signing certificate, as the key restriction expects (hex, no colons). */
    @Suppress("DEPRECATION")
    private fun certSha1(ctx: Context): String {
        cert?.let { return it }
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return ""
        val hex = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        cert = hex
        return hex
    }
}
