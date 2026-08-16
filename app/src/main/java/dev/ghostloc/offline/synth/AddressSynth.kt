package dev.ghostloc.offline.synth

import android.location.Address
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.Prefs
import java.util.ArrayList
import java.util.Locale

/** Geocoder result synthesis: one address pinned to the spoofed coordinate. */
object AddressSynth {

    fun makeAddressList(): List<Address> {
        val prefs = ModuleRuntime.prefs()
        val lat = Double.fromBits(prefs?.getLong(Prefs.LAT, Prefs.DEFAULT_LAT.toRawBits()) ?: Prefs.DEFAULT_LAT.toRawBits())
        val lon = Double.fromBits(prefs?.getLong(Prefs.LON, Prefs.DEFAULT_LON.toRawBits()) ?: Prefs.DEFAULT_LON.toRawBits())
        val address = Address(Locale.getDefault())
        address.latitude = lat
        address.longitude = lon
        address.featureName = "GhostLoc"
        address.setAddressLine(0, String.format(Locale.US, "GhostLoc %.6f, %.6f", lat, lon))
        return ArrayList(listOf(address))
    }

    fun dispatchGeocodeListener(args: Array<Any?>?, addresses: List<Address>) {
        if (args == null) {
            return
        }
        for (arg in args) {
            if (arg == null) {
                continue
            }
            tryInvoke(arg, "onGeocode", arrayOf(MutableList::class.java), arrayOf(addresses))
        }
    }
}
