package dev.kairo.myra.brain.life

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import dev.kairo.myra.Fail
import dev.kairo.myra.brain.BrainEnv
import dev.kairo.myra.brain.BrainTool
import dev.kairo.myra.brain.Risk
import dev.kairo.myra.brain.ToolModule
import dev.kairo.myra.brain.prop
import dev.kairo.myra.brain.schema
import dev.kairo.myra.brain.tool
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Places in the app: "Ghar kitni door hai", "Connaught Place ka rasta", and named places like "ghar" or "office".
 * Distance is straight-line (not road distance). Navigation opens Google Maps (or any maps app) for turn-by-turn.
 * Needs location permission for distance. Nothing is sent anywhere except the phone's own geocoder and Maps.
 */
class PlacesModule(private val env: BrainEnv) : ToolModule {
    private val ctx: Context get() = env.ctx
    private val file = File(env.dir, "places.json")
    private val lock = Any()

    private fun loadPlaces(): JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }

    /** A saved name ("ghar") becomes its address; anything else is searched as typed. */
    private fun resolve(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) throw Fail("Which place do you mean?")
        synchronized(lock) { return loadPlaces().optString(q.lowercase(Locale.ROOT), q) }
    }

    private fun placeSave(a: JSONObject): String {
        val name = a.optString("name").trim().lowercase(Locale.ROOT)
        val address = a.optString("address").trim()
        if (name.isEmpty() || address.isEmpty()) throw Fail("A saved place needs a name and an address.")
        synchronized(lock) {
            val o = loadPlaces()
            o.put(name, address)
            file.writeText(o.toString(1))
        }
        return "Saved $name as $address."
    }

    private fun geocode(query: String): android.location.Address {
        if (!Geocoder.isPresent()) throw Fail("Place search is not available on this phone.")
        val found = try { Geocoder(ctx, Locale.getDefault()).getFromLocationName(query, 1) } catch (e: Exception) { null }
        return found?.firstOrNull() ?: throw Fail("I couldn't find $query on the map.")
    }

    private fun here(): Location {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw Fail("I need location permission to know where you are. Please allow it for Myraa.")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }
        return loc ?: throw Fail("I can't get your location right now. Turn on location and try again.")
    }

    private fun distanceTo(a: JSONObject): String {
        val query = resolve(a.optString("place"))
        val target = geocode(query)
        val me = here()
        val results = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, target.latitude, target.longitude, results)
        val m = results[0].toDouble()
        val text = if (m >= 1000) "%.1f km".format(Locale.US, m / 1000) else "${m.toInt()} metres"
        return "$query is about $text away in a straight line from you."
    }

    private fun navigateTo(a: JSONObject): String {
        val query = resolve(a.optString("place"))
        val target = geocode(query)
        val lat = target.latitude
        val lng = target.longitude
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d"))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(maps)
        } catch (e: ActivityNotFoundException) {
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(query)})"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(geo) } catch (e2: ActivityNotFoundException) { throw Fail("No maps app is installed on this phone.") }
        }
        return "Opening directions to $query."
    }

    override fun tools(): List<BrainTool> = listOf(
        tool("distance_to", "How far a place is from the user's current location (straight line). Works with saved names like ghar or office.", Risk.LOW,
            schema(listOf("place"), "place" to prop("string"))) { distanceTo(it) },
        tool("navigate_to", "Open turn-by-turn directions to a place in Google Maps. Works with saved names like ghar.", Risk.LOW,
            schema(listOf("place"), "place" to prop("string"))) { navigateTo(it) },
        tool("place_save", "Save a short name for an address, like ghar or office, so the user can say just that.", Risk.LOW,
            schema(listOf("name", "address"), "name" to prop("string"), "address" to prop("string"))) { placeSave(it) },
    )
}
