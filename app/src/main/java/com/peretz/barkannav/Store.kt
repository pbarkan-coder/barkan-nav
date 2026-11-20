package com.peretz.barkannav

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/** A park-and-ride lot. [query] is what Waze and Google Maps search for; [link] is an optional Google Maps link. */
data class Lot(val name: String, val query: String, val link: String = "")

/** Saved lots, recent destinations and the current choice, kept in the app's private preferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("barkan_nav", Context.MODE_PRIVATE)

    val lots = mutableStateListOf<Lot>().apply { addAll(loadLots()) }
    val recents = mutableStateListOf<String>().apply { addAll(loadStrings("recents")) }
    val destination = mutableStateOf(prefs.getString("destination", "") ?: "")
    val selected = mutableStateOf(prefs.getString("selected", "") ?: "")

    val selectedLot: Lot? get() = lots.firstOrNull { it.name == selected.value }

    fun setDestination(text: String) {
        destination.value = text
        prefs.edit().putString("destination", text).apply()
    }

    fun select(lot: Lot) {
        selected.value = lot.name
        prefs.edit().putString("selected", lot.name).apply()
    }

    /** Adds or replaces (same name) a lot and selects it. */
    fun addLot(lot: Lot) {
        val i = lots.indexOfFirst { it.name == lot.name }
        if (i >= 0) lots[i] = lot else lots.add(0, lot)
        saveLots()
        select(lot)
    }

    fun removeLot(lot: Lot) {
        lots.remove(lot)
        saveLots()
        if (selected.value == lot.name) {
            selected.value = ""
            prefs.edit().remove("selected").apply()
        }
    }

    fun rememberDestination(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        recents.remove(t)
        recents.add(0, t)
        while (recents.size > 8) recents.removeAt(recents.lastIndex)
        prefs.edit().putString("recents", JSONArray(recents.toList()).toString()).apply()
    }

    fun forgetDestination(text: String) {
        recents.remove(text)
        prefs.edit().putString("recents", JSONArray(recents.toList()).toString()).apply()
    }

    private fun saveLots() {
        val arr = JSONArray()
        lots.forEach { arr.put(JSONObject().put("name", it.name).put("query", it.query).put("link", it.link)) }
        prefs.edit().putString("lots", arr.toString()).apply()
    }

    private fun loadLots(): List<Lot> = try {
        val arr = JSONArray(prefs.getString("lots", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
            val name = o.optString("name").trim()
            if (name.isEmpty()) null else Lot(name, o.optString("query").ifBlank { name }, o.optString("link"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadStrings(key: String): List<String> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private val urlRe = Regex("""https?://\S+""")

        /**
         * Turns text shared from Google Maps (usually the place name, maybe an address,
         * then a maps.app.goo.gl link) into a lot. Returns null if there is no name.
         */
        fun lotFromShared(text: String): Lot? {
            val link = urlRe.find(text)?.value
                ?.takeIf { it.startsWith("https://maps.app.goo.gl/") || it.startsWith("https://goo.gl/maps/") || it.startsWith("https://www.google.com/maps") || it.startsWith("https://maps.google.com/") }
                ?: ""
            val lines = text.replace(urlRe, "").lines().map { it.trim().trim('"', '“', '”') }.filter { it.isNotEmpty() }
            val name = lines.firstOrNull()?.take(120) ?: return null
            val query = lines.take(2).joinToString(", ").take(200)
            return Lot(name, query, link)
        }
    }
}
