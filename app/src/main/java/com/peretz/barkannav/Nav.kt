package com.peretz.barkannav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Hands each leg of the trip to Waze or Google Maps. */
object Nav {
    private const val WAZE = "com.waze"
    private const val GMAPS = "com.google.android.apps.maps"
    private fun enc(s: String) = Uri.encode(s.trim())

    /** Leg 1 by car, in Waze. */
    fun driveWaze(ctx: Context, lot: Lot) =
        open(ctx, "https://waze.com/ul?q=${enc(lot.query)}&navigate=yes", WAZE)

    /** Leg 1 by car, in Google Maps. */
    fun driveGoogle(ctx: Context, lot: Lot) =
        open(ctx, "https://www.google.com/maps/dir/?api=1&destination=${enc(lot.query)}&travelmode=driving&dir_action=navigate", GMAPS)

    /** Leg 2: public transport from the lot to the destination. */
    fun transit(ctx: Context, lot: Lot, destination: String) =
        open(ctx, "https://www.google.com/maps/dir/?api=1&origin=${enc(lot.query)}&destination=${enc(destination)}&travelmode=transit", GMAPS)

    /** Searches Google Maps for park-and-ride lots around the map's current view. */
    fun searchLots(ctx: Context) =
        open(ctx, "https://www.google.com/maps/search/?api=1&query=${enc("חניון חנה וסע")}", GMAPS)

    /** Shows a lot on the map. */
    fun showLot(ctx: Context, lot: Lot) {
        val url = lot.link.ifBlank { "https://www.google.com/maps/search/?api=1&query=${enc(lot.query)}" }
        open(ctx, url, GMAPS)
    }

    private fun open(ctx: Context, url: String, preferredPackage: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(intent).setPackage(preferredPackage))
            return
        } catch (e: ActivityNotFoundException) {
            // app not installed: fall back to the browser
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "לא נמצאה אפליקציה שיכולה לפתוח את המסלול", Toast.LENGTH_SHORT).show()
        }
    }
}
