package dev.ghostloc.offline.synth

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentity
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.ArraySet
import dev.ghostloc.offline.core.ModuleRuntime
import dev.ghostloc.offline.core.ModuleRuntime.newInstance
import dev.ghostloc.offline.core.ModuleRuntime.setFieldIfPresent
import dev.ghostloc.offline.core.ModuleRuntime.tryInstanceInvoke
import dev.ghostloc.offline.core.ModuleRuntime.tryInvoke
import dev.ghostloc.offline.core.Prefs
import java.util.Arrays

/**
 * Deterministic LTE/NR cell synthesis seeded by the current coordinate, so the
 * same spoofed position always yields the same internally-consistent cell
 * identity and signal readings.
 */
object CellSynth {

    data class CarrierPreset(
        val mcc: String,
        val mnc: String,
        val countryIso: String,
        val operatorName: String,
        val lteBand: Int,
        val lteBandwidthKhz: Int,
        val nrArfcnBase: Int,
        val nrBand: Int,
    )

    data class CellProfile(
        val mcc: String,
        val mnc: String,
        val countryIso: String,
        val operatorName: String,
        val nrEnabled: Boolean,
        val lteTac: Int,
        val lteCi: Int,
        val ltePci: Int,
        val lteEarfcn: Int,
        val lteBands: IntArray,
        val lteBandwidth: Int,
        val lteRssi: Int,
        val lteRsrp: Int,
        val lteRsrq: Int,
        val lteRssnr: Int,
        val lteTimingAdvance: Int,
        val nrTac: Int,
        val nrNci: Long,
        val nrPci: Int,
        val nrArfcn: Int,
        val nrBands: IntArray,
        val nrSsRsrp: Int,
        val nrSsRsrq: Int,
        val nrSsSinr: Int,
        val nrCsiRsrp: Int,
        val nrCsiRsrq: Int,
        val nrCsiSinr: Int,
        val nrTimingAdvance: Int,
    ) {
        val operatorNumeric: String get() = mcc + mnc
    }

    fun currentCellProfile(): CellProfile {
        val prefs: SharedPreferences = ModuleRuntime.prefs() ?: return defaultProfile()
        val lat = ModuleRuntime.currentLat()
        val lon = ModuleRuntime.currentLon()
        val preset = carrierPresetFor(lat, lon)
        val mcc = stringPreference(prefs, Prefs.CELL_MCC, preset.mcc)
        val mnc = stringPreference(prefs, Prefs.CELL_MNC, preset.mnc)
        val countryIso = stringPreference(prefs, Prefs.CELL_COUNTRY_ISO, preset.countryIso)
        val operatorName = stringPreference(prefs, Prefs.CELL_OPERATOR_NAME, preset.operatorName)
        val nrEnabled = Build.VERSION.SDK_INT >= 29 && prefs.getBoolean(Prefs.CELL_ENABLE_NR, true)
        val seed = mixSeed(lat, lon, mcc, mnc, operatorName)
        return CellProfile(
            mcc = mcc,
            mnc = mnc,
            countryIso = countryIso,
            operatorName = operatorName,
            nrEnabled = nrEnabled,
            lteTac = 1000 + positiveInt(seed, 60000 - 1000),
            lteCi = 1 + positiveInt(seed.rotateLeft(7), 0x0ffffffe),
            ltePci = positiveInt(seed.rotateLeft(13), 503),
            lteEarfcn = 1200 + positiveInt(seed.rotateLeft(17), 3200),
            lteBands = intArrayOf(preset.lteBand),
            lteBandwidth = preset.lteBandwidthKhz,
            lteRssi = -62 - positiveInt(seed.rotateLeft(31), 18),
            lteRsrp = -88 - positiveInt(seed.rotateLeft(19), 18),
            lteRsrq = -8 - positiveInt(seed.rotateLeft(23), 8),
            lteRssnr = 50 + positiveInt(seed.rotateLeft(29), 160),
            lteTimingAdvance = 1 + positiveInt(seed.rotateLeft(37), 64),
            nrTac = 2000 + positiveInt(seed.rotateLeft(43), 60000 - 2000),
            nrNci = 1L + positiveLong(seed.rotateLeft(41), 68_719_476_734L),
            nrPci = positiveInt(seed.rotateLeft(47), 1008),
            nrArfcn = preset.nrArfcnBase + positiveInt(seed.rotateLeft(53), 20000),
            nrBands = intArrayOf(preset.nrBand),
            nrSsRsrp = -86 - positiveInt(seed.rotateLeft(59), 20),
            nrSsRsrq = -10 - positiveInt(seed.rotateLeft(61), 8),
            nrSsSinr = 30 + positiveInt(seed.rotateLeft(5), 140),
            nrCsiRsrp = -86 - positiveInt(seed.rotateLeft(59), 20) + 2,
            nrCsiRsrq = -10 - positiveInt(seed.rotateLeft(61), 8) + 1,
            nrCsiSinr = 30 + positiveInt(seed.rotateLeft(5), 140) + 5,
            nrTimingAdvance = 10 + positiveInt(seed.rotateLeft(11), 120),
        )
    }

    private fun defaultProfile(): CellProfile {
        val preset = carrierPresetFor(Prefs.DEFAULT_LAT, Prefs.DEFAULT_LON)
        val seed = mixSeed(Prefs.DEFAULT_LAT, Prefs.DEFAULT_LON, preset.mcc, preset.mnc, preset.operatorName)
        return CellProfile(
            preset.mcc, preset.mnc, preset.countryIso, preset.operatorName, true,
            1000 + positiveInt(seed, 59000),
            1 + positiveInt(seed.rotateLeft(7), 0x0ffffffe),
            positiveInt(seed.rotateLeft(13), 503),
            1200 + positiveInt(seed.rotateLeft(17), 3200),
            intArrayOf(preset.lteBand), preset.lteBandwidthKhz,
            -62 - positiveInt(seed.rotateLeft(31), 18),
            -88 - positiveInt(seed.rotateLeft(19), 18),
            -8 - positiveInt(seed.rotateLeft(23), 8),
            50 + positiveInt(seed.rotateLeft(29), 160),
            1 + positiveInt(seed.rotateLeft(37), 64),
            2000 + positiveInt(seed.rotateLeft(43), 58000),
            1L + positiveLong(seed.rotateLeft(41), 68_719_476_734L),
            positiveInt(seed.rotateLeft(47), 1008),
            preset.nrArfcnBase + positiveInt(seed.rotateLeft(53), 20000),
            intArrayOf(preset.nrBand),
            -86 - positiveInt(seed.rotateLeft(59), 20),
            -10 - positiveInt(seed.rotateLeft(61), 8),
            30 + positiveInt(seed.rotateLeft(5), 140),
            -84, -9, 35,
            10 + positiveInt(seed.rotateLeft(11), 120),
        )
    }

    fun makeCellInfoList(): List<CellInfo> {
        val profile = currentCellProfile()
        val cells = ArrayList<CellInfo>()
        makeLteCellInfo(profile)?.let { cells.add(it) }
        if (profile.nrEnabled) {
            makeNrCellInfo(profile)?.let { cells.add(it) }
        }
        return cells
    }

    fun makeLteCellInfo(profile: CellProfile): CellInfoLte? {
        val info = newInstance(CellInfoLte::class.java)
        if (info == null) {
            ModuleRuntime.log("CellInfoLte ctor unavailable")
            return null
        }
        applyBaseCellInfo(info, true, CellInfo.CONNECTION_PRIMARY_SERVING)
        val identity = newInstance(CellIdentityLte::class.java)
        val signal = newInstance(CellSignalStrengthLte::class.java)
        if (identity == null || signal == null) {
            ModuleRuntime.log("LTE identity/signal ctor unavailable")
            return info
        }
        applyBaseCellIdentity(identity, profile, "CellIdentityLte")
        setFieldIfPresent(identity, "mCi", profile.lteCi)
        setFieldIfPresent(identity, "mPci", profile.ltePci)
        setFieldIfPresent(identity, "mTac", profile.lteTac)
        setFieldIfPresent(identity, "mEarfcn", profile.lteEarfcn)
        setFieldIfPresent(identity, "mBandwidth", profile.lteBandwidth)
        setFieldIfPresent(identity, "mBands", profile.lteBands)
        setFieldIfPresent(identity, "mAdditionalPlmns", ArraySet<String>())
        setFieldIfPresent(signal, "mRssi", profile.lteRssi)
        setFieldIfPresent(signal, "mRsrp", profile.lteRsrp)
        setFieldIfPresent(signal, "mRsrq", profile.lteRsrq)
        setFieldIfPresent(signal, "mRssnr", profile.lteRssnr)
        setFieldIfPresent(signal, "mCqi", 9)
        setFieldIfPresent(signal, "mCqiTableIndex", 1)
        setFieldIfPresent(signal, "mTimingAdvance", profile.lteTimingAdvance)
        setFieldIfPresent(signal, "mLevel", 4)
        setFieldIfPresent(signal, "mParametersUseForLevel", 1)
        setFieldIfPresent(info, "mCellIdentityLte", identity)
        setFieldIfPresent(info, "mCellSignalStrengthLte", signal)
        return info
    }

    fun makeNrCellInfo(profile: CellProfile): CellInfoNr? {
        val info = newInstance(CellInfoNr::class.java)
        if (info == null) {
            ModuleRuntime.log("CellInfoNr ctor unavailable")
            return null
        }
        applyBaseCellInfo(info, false, CellInfo.CONNECTION_SECONDARY_SERVING)
        val identity = newInstance(CellIdentityNr::class.java)
        val signal = newInstance(CellSignalStrengthNr::class.java)
        if (identity == null || signal == null) {
            ModuleRuntime.log("NR identity/signal ctor unavailable")
            return info
        }
        applyBaseCellIdentity(identity, profile, "CellIdentityNr")
        setFieldIfPresent(identity, "mNci", profile.nrNci)
        setFieldIfPresent(identity, "mPci", profile.nrPci)
        setFieldIfPresent(identity, "mTac", profile.nrTac)
        setFieldIfPresent(identity, "mNrArfcn", profile.nrArfcn)
        setFieldIfPresent(identity, "mBands", profile.nrBands)
        setFieldIfPresent(identity, "mAdditionalPlmns", ArraySet<String>())
        setFieldIfPresent(signal, "mSsRsrp", profile.nrSsRsrp)
        setFieldIfPresent(signal, "mSsRsrq", profile.nrSsRsrq)
        setFieldIfPresent(signal, "mSsSinr", profile.nrSsSinr)
        setFieldIfPresent(signal, "mCsiRsrp", profile.nrCsiRsrp)
        setFieldIfPresent(signal, "mCsiRsrq", profile.nrCsiRsrq)
        setFieldIfPresent(signal, "mCsiSinr", profile.nrCsiSinr)
        setFieldIfPresent(signal, "mTimingAdvance", profile.nrTimingAdvance)
        setFieldIfPresent(signal, "mLevel", 4)
        setFieldIfPresent(signal, "mParametersUseForLevel", 1)
        setFieldIfPresent(info, "mCellIdentity", identity)
        setFieldIfPresent(info, "mCellSignalStrength", signal)
        return info
    }

    private fun applyBaseCellInfo(cellInfo: CellInfo, registered: Boolean, connectionStatus: Int) {
        tryInvoke(cellInfo, "setRegistered", arrayOf(java.lang.Boolean.TYPE), arrayOf(registered))
        tryInvoke(cellInfo, "setTimeStamp", arrayOf(java.lang.Long.TYPE), arrayOf(System.currentTimeMillis()))
        tryInvoke(cellInfo, "setCellConnectionStatus", arrayOf(java.lang.Integer.TYPE), arrayOf(connectionStatus))
        setFieldIfPresent(cellInfo, "mRegistered", registered)
        setFieldIfPresent(cellInfo, "mTimeStamp", System.currentTimeMillis())
        setFieldIfPresent(cellInfo, "mCellConnectionStatus", connectionStatus)
    }

    private fun applyBaseCellIdentity(identity: Any, profile: CellProfile, tag: String) {
        setFieldIfPresent(identity, "mTag", tag)
        setFieldIfPresent(identity, "mType", if (tag.endsWith("Nr")) 6 else 3)
        setFieldIfPresent(identity, "mMccStr", profile.mcc)
        setFieldIfPresent(identity, "mMncStr", profile.mnc)
        setFieldIfPresent(identity, "mAlphaLong", profile.operatorName)
        setFieldIfPresent(identity, "mAlphaShort", profile.operatorName)
        setFieldIfPresent(identity, "mGlobalCellId", profile.operatorNumeric)
    }

    fun dispatchCellInfoCallback(args: Array<Any?>?, cells: List<CellInfo>): Boolean {
        if (args == null) {
            return false
        }
        var executor: java.util.concurrent.Executor? = null
        var callback: Any? = null
        for (arg in args) {
            if (arg is java.util.concurrent.Executor) {
                executor = arg
            } else if (arg != null && ModuleRuntime.hasMethod(arg.javaClass, "onCellInfo", MutableList::class.java)) {
                callback = arg
            }
        }
        val target = callback ?: return false
        val task = Runnable {
            if (!tryInvoke(target, "onCellInfo", arrayOf(MutableList::class.java), arrayOf(cells))) {
                ModuleRuntime.log("cell callback dispatch failed " + target.javaClass.name)
            }
        }
        executor?.execute(task) ?: task.run()
        return true
    }

    fun makeServiceState(): ServiceState {
        val profile = currentCellProfile()
        val serviceState = ServiceState()
        tryInvoke(serviceState, "setState", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(ServiceState.STATE_IN_SERVICE))
        tryInvoke(serviceState, "setVoiceRegState", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(ServiceState.STATE_IN_SERVICE))
        tryInvoke(serviceState, "setDataRegState", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(ServiceState.STATE_IN_SERVICE))
        tryInvoke(serviceState, "setRoaming", arrayOf(java.lang.Boolean.TYPE), arrayOf(false))
        tryInvoke(
            serviceState, "setOperatorName",
            arrayOf(String::class.java, String::class.java, String::class.java),
            arrayOf(profile.operatorName, profile.operatorName, profile.operatorNumeric),
        )
        val lteIdentity = makeLteCellIdentity(profile)
        val voiceRegistration = makeNetworkRegistrationInfo(
            profile,
            NetworkRegistrationInfo.DOMAIN_CS,
            TelephonyManager.NETWORK_TYPE_LTE,
            Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_VOICE, NetworkRegistrationInfo.SERVICE_TYPE_SMS),
            lteIdentity,
            false,
        )
        if (voiceRegistration != null) {
            tryInvoke(
                serviceState, "addNetworkRegistrationInfo",
                arrayOf(NetworkRegistrationInfo::class.java), arrayOf(voiceRegistration),
            )
        }
        val dataIdentity: Any? = if (profile.nrEnabled) makeNrCellIdentity(profile) else lteIdentity
        val dataNetworkType = if (profile.nrEnabled) TelephonyManager.NETWORK_TYPE_NR else TelephonyManager.NETWORK_TYPE_LTE
        val dataRegistration = makeNetworkRegistrationInfo(
            profile,
            NetworkRegistrationInfo.DOMAIN_PS,
            dataNetworkType,
            Arrays.asList(NetworkRegistrationInfo.SERVICE_TYPE_DATA, NetworkRegistrationInfo.SERVICE_TYPE_MMS),
            dataIdentity,
            profile.nrEnabled,
        )
        if (dataRegistration != null) {
            tryInvoke(
                serviceState, "addNetworkRegistrationInfo",
                arrayOf(NetworkRegistrationInfo::class.java), arrayOf(dataRegistration),
            )
        }
        return serviceState
    }

    private fun makeLteCellIdentity(profile: CellProfile): CellIdentityLte? {
        return makeLteCellInfo(profile)?.cellIdentity
    }

    /** LTE identity for hook layers that must hand out a CellIdentity-typed value. */
    fun currentLteCellIdentity(): CellIdentityLte? = makeLteCellIdentity(currentCellProfile())

    /**
     * Legacy bundle-shaped cell location for the deprecated push/pull channel
     * (`TelephonyRegistry#notifyCellLocation*`, `PhoneStateListener#
     * onCellLocationChanged`, `TelephonyManager#getCellLocation`). The original
     * shipped a fake CDMA bundle with the spoofed coordinates embedded; LTE
     * identities carry no coordinates, so this mirrors the GSM key format with
     * the same TAC/CI the structured synthesis uses, keeping every channel
     * internally consistent.
     */
    fun makeCellLocationBundle(): Bundle {
        val profile = currentCellProfile()
        val bundle = Bundle()
        bundle.putInt("lac", profile.lteTac)
        bundle.putInt("cid", (profile.lteCi and 0x7fffffff).toInt())
        bundle.putInt("psc", -1)
        return bundle
    }

    private fun makeNrCellIdentity(profile: CellProfile): CellIdentityNr? {
        return makeNrCellInfo(profile)?.cellIdentity as? CellIdentityNr
    }

    private fun makeNetworkRegistrationInfo(
        profile: CellProfile,
        domain: Int,
        accessNetworkTechnology: Int,
        services: List<Int>,
        cellIdentity: Any?,
        markNrConnected: Boolean,
    ): Any? {
        val builderClass = ModuleRuntime.findClass(
            CellSynth::class.java.classLoader,
            "android.telephony.NetworkRegistrationInfo\$Builder",
        ) ?: return null
        val builder = ModuleRuntime.newInstance(builderClass) ?: return null
        tryInvoke(builder, "setDomain", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(domain))
        tryInvoke(
            builder, "setTransportType", arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(AccessNetworkConstants.TRANSPORT_TYPE_WWAN),
        )
        tryInvoke(builder, "setRegistrationState", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(1))
        tryInvoke(builder, "setAccessNetworkTechnology", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(accessNetworkTechnology))
        tryInvoke(builder, "setRejectCause", arrayOf(Int::class.javaPrimitiveType!!), arrayOf(0))
        tryInvoke(builder, "setEmergencyOnly", arrayOf(java.lang.Boolean.TYPE), arrayOf(false))
        tryInvoke(builder, "setAvailableServices", arrayOf(List::class.java), arrayOf(services))
        if (cellIdentity is CellIdentity) {
            tryInvoke(builder, "setCellIdentity", arrayOf(CellIdentity::class.java), arrayOf(cellIdentity))
        }
        tryInvoke(builder, "setRegisteredPlmn", arrayOf(String::class.java), arrayOf(profile.operatorNumeric))
        val registrationInfo = tryInstanceInvoke(builder, "build", emptyArray(), emptyArray())
        if (registrationInfo != null && markNrConnected) {
            tryInvoke(
                registrationInfo, "setNrState", arrayOf(Int::class.javaPrimitiveType!!),
                arrayOf(NetworkRegistrationInfo.NR_STATE_CONNECTED),
            )
        }
        return registrationInfo
    }

    private fun carrierPresetFor(lat: Double, lon: Double): CarrierPreset {
        if (lat >= 29.0 && lat <= 46.5 && lon >= 129.0 && lon <= 146.5) {
            return CarrierPreset("440", "10", "jp", "NTT DOCOMO", 3, 20_000, 640_000, 78)
        }
        if (lat >= 18.0 && lat <= 54.5 && lon >= 73.0 && lon <= 135.5) {
            return CarrierPreset("460", "00", "cn", "China Mobile", 41, 20_000, 627_000, 78)
        }
        if (lat >= 32.0 && lat <= 43.0 && lon >= 124.0 && lon <= 132.5) {
            return CarrierPreset("450", "05", "kr", "SK Telecom", 3, 15_000, 634_000, 78)
        }
        if (lat >= 49.0 && lat <= 61.0 && lon >= -9.0 && lon <= 3.5) {
            return CarrierPreset("234", "15", "gb", "Vodafone UK", 20, 20_000, 635_000, 78)
        }
        if (lat >= 18.0 && lat <= 72.0 && lon >= -170.0 && lon <= -60.0) {
            return CarrierPreset("310", "260", "us", "T-Mobile", 2, 20_000, 636_000, 41)
        }
        return CarrierPreset("001", "01", "us", "GhostLoc", 3, 15_000, 635_000, 78)
    }

    private fun stringPreference(prefs: SharedPreferences, key: String, fallback: String): String {
        val value = prefs.getString(key, fallback)
        return if (value.isNullOrBlank()) fallback else value.trim()
    }

    private fun mixSeed(lat: Double, lon: Double, mcc: String, mnc: String, operatorName: String): Long {
        // Golden-ratio multiplier, written in decimal because Kotlin rejects
        // hex Long literals that wrap past Long.MAX_VALUE (Java: 0x9e3779b97f4a7c15L).
        val goldenRatio = -7046029254386353131L
        var seed = lat.toBits() * goldenRatio
        seed = seed xor lon.toBits().rotateLeft(29)
        seed = seed xor (mcc.hashCode().toLong() shl 32)
        seed = seed xor mnc.hashCode().toLong()
        seed = seed xor operatorName.hashCode().toLong().rotateLeft(11)
        return seed xor 0x51ed270b7f4a7c15L
    }

    private fun positiveInt(seed: Long, bound: Int): Int {
        return if (bound <= 0) 0 else Math.floorMod(seed, bound.toLong()).toInt()
    }

    private fun positiveLong(seed: Long, bound: Long): Long {
        return if (bound <= 0) 0L else Math.floorMod(seed, bound)
    }
}
