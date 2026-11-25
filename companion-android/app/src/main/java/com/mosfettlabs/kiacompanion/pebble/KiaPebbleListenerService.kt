package com.mosfettlabs.kiacompanion.pebble

import android.content.Context
import android.util.Log
import com.mosfettlabs.kiacompanion.KiaApi
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import android.content.SharedPreferences

/**
 * Serves the Kia watchapp: on a status request, ensures a live Kia session
 * (auto re-login from stored credentials), fetches cached vehicle status and
 * pushes it to the watch. Remote actions arrive in K3.
 */
class KiaPebbleListenerService : BasePebbleListenerService() {
    private val api = KiaApi()

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != KiaPebbleProtocol.APP_UUID) {
            return ReceiveResult.Nack
        }
        PebbleWatchStore.saveLastWatch(this@KiaPebbleListenerService, watch)
        if (data[KiaPebbleProtocol.WATCH_READY] == null &&
            data[KiaPebbleProtocol.STATUS_REQUEST] == null
        ) {
            return ReceiveResult.Ack
        }
        val actionItem = data[KiaPebbleProtocol.ACTION]
        if (actionItem != null) {
            val actionType = ((actionItem as? PebbleDictionaryItem.UInt8)?.value ?: 0u).toInt()
            if (actionType == 0) return ReceiveResult.Ack
            val temp = (data[KiaPebbleProtocol.ACTION_TEMP] as? PebbleDictionaryItem.Int8)?.value ?: 0
            val duration = ((data[KiaPebbleProtocol.ACTION_DURATION] as? PebbleDictionaryItem.UInt8)?.value ?: 0u).toInt()
            Log.d(TAG, "Action from watch: type=$actionType temp=$temp duration=$duration")
            val sender = DefaultPebbleSender(this)
            try {
                withContext(Dispatchers.IO) {
                    runWatchAction(this@KiaPebbleListenerService, sender, watch, actionType, temp, duration)
                }
            } finally {
                sender.close()
            }
            return ReceiveResult.Ack
        }
        val statusRequest = data[KiaPebbleProtocol.STATUS_REQUEST] != null
        if (!statusRequest) {
            // Watch merely announced itself; acknowledge silently.
            return ReceiveResult.Ack
        }
        Log.d(TAG, "Status request from watch")
        val sender = DefaultPebbleSender(this)
        try {
            withContext(Dispatchers.IO) {
                try {
                    val (token, vehicle) = ensureSession(this@KiaPebbleListenerService)
                    val status = api.getCachedStatus(token, vehicle)
                    val payload = mapOf(
                        KiaPebbleProtocol.NAME to PebbleDictionaryItem.Text(status.vehicle.name.take(40)),
                        KiaPebbleProtocol.LOCKED to PebbleDictionaryItem.UInt8(if (status.locked) 1 else 0),
                        KiaPebbleProtocol.FUEL to PebbleDictionaryItem.UInt8(status.fuelLevelPercent ?: -1),
                        KiaPebbleProtocol.ODO to PebbleDictionaryItem.UInt32(
                            (status.odometerMiles?.toInt() ?: 0).toLong()),
                        KiaPebbleProtocol.UPDATED to PebbleDictionaryItem.Text(
                            status.lastUpdatedUtc?.take(28) ?: "unknown"),
                    )
                    sender.sendDataToPebble(KiaPebbleProtocol.APP_UUID, payload, listOf(watch))
                } catch (e: Exception) {
                    Log.w(TAG, "status failed: ${e.message}")
                    sender.sendDataToPebble(
                        KiaPebbleProtocol.APP_UUID,
                        mapOf(KiaPebbleProtocol.INFO to PebbleDictionaryItem.Text(
                            (e.message ?: "status failed").take(60))),
                        listOf(watch),
                    )
                }
            }
        } finally {
            sender.close()
        }
        return ReceiveResult.Ack
    }

    private suspend fun runWatchAction(
        context: Context,
        sender: DefaultPebbleSender,
        watch: WatchIdentifier,
        actionType: Int,
        temp: Byte,
        duration: Int,
    ) {
        suspend fun info(text: String) {
            sender.sendDataToPebble(
                KiaPebbleProtocol.APP_UUID,
                mapOf(KiaPebbleProtocol.INFO to PebbleDictionaryItem.Text(text.take(60))),
                listOf(watch),
            )
        }
        suspend fun pushStatus(status: KiaApi.VehicleStatus) {
            val payload = mapOf(
                KiaPebbleProtocol.NAME to PebbleDictionaryItem.Text(status.vehicle.name.take(40)),
                KiaPebbleProtocol.LOCKED to PebbleDictionaryItem.UInt8(if (status.locked) 1 else 0),
                KiaPebbleProtocol.FUEL to PebbleDictionaryItem.UInt8(status.fuelLevelPercent ?: -1),
                KiaPebbleProtocol.ODO to PebbleDictionaryItem.UInt32(
                    (status.odometerMiles?.toInt() ?: 0).toLong()),
                KiaPebbleProtocol.UPDATED to PebbleDictionaryItem.Text(
                    status.lastUpdatedUtc?.take(28) ?: "unknown"),
            )
            sender.sendDataToPebble(KiaPebbleProtocol.APP_UUID, payload, listOf(watch))
        }
        try {
            val (token, vehicle) = ensureSession(context)
            val xid: String = when (actionType) {
                1 -> { info("Locking… Kia says 1–2 min"); api.lockAction(token, vehicle, true) }
                2 -> { info("Unlocking… Kia says 1–2 min"); api.lockAction(token, vehicle, false) }
                3 -> {
                    val tempValue = if (temp.toInt() == 0) "LOW" else temp.toInt().toString()
                    info("Starting engine (${tempValue.take(4)})…")
                    api.startClimate(token, vehicle, tempValue, temp.toInt() != 0, if (duration in 1..30) duration else 10)
                }
                4 -> { info("Stopping engine…"); api.stopClimate(token, vehicle) }
                else -> throw KiaApi.ApiError("Unknown action $actionType")
            }
            info("Kia is executing… (1–2 min)")
            val outcome = api.checkActionStatus(token, vehicle, xid, 150)
            when (outcome) {
                KiaApi.OrderStatus.SUCCESS -> info("Done ✓")
                KiaApi.OrderStatus.FAILED -> info("Kia reported failure — try again")
                else -> info("Still pending — check the car/status later")
            }
            try {
                pushStatus(api.getCachedStatus(token, vehicle))
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            info((e.message ?: "action failed").take(60))
        }
    }

    /** Returns a live token + first vehicle, re-logging in when needed. */
    private fun ensureSession(context: Context): Pair<KiaApi.Token, KiaApi.Vehicle> {
        val prefs = context.getSharedPreferences("kia_companion_settings", Context.MODE_PRIVATE)
        val username = prefs.getString("username", "").orEmpty()
        val password = prefs.getString("password", "").orEmpty()
        val pin = prefs.getString("pin", "").orEmpty()
        val deviceId = prefs.getString("device_id", null)
        val sid = prefs.getString("sid", null)
        val expiry = prefs.getLong("sid_expiry", 0)
        val key = prefs.getString("vehicle_key", null)
        val name = prefs.getString("vehicle_name", null)
        val model = prefs.getString("vehicle_model", null)
        val id = prefs.getString("vehicle_id", null)
        if (username.isBlank() || password.isBlank()) {
            throw KiaApi.ApiError("Open Kia Companion and log in first.")
        }
        val token = if (!sid.isNullOrBlank() && expiry > System.currentTimeMillis() && !deviceId.isNullOrBlank()) {
            KiaApi.Token(
                username = username,
                password = password,
                pin = pin,
                deviceId = deviceId,
                sid = sid,
                validUntilMs = expiry,
            )
        } else {
            val fresh = api.login(username, password, pin, deviceId) as? KiaApi.Token
                ?: throw KiaApi.ApiError("Kia wants device verification again — open the phone app and log in.")
            prefs.edit()
                .putString("device_id", fresh.deviceId)
                .putString("sid", fresh.sid)
                .putLong("sid_expiry", fresh.validUntilMs)
                .apply()
            fresh
        }
        val vehicle = if (!key.isNullOrBlank() && !name.isNullOrBlank()) {
            KiaApi.Vehicle(id = id ?: key, name = name, model = model ?: name, key = key)
        } else {
            val vehicles = api.getVehicles(token)
            val first = vehicles.first()
            prefs.edit()
                .putString("vehicle_key", first.key)
                .putString("vehicle_name", first.name)
                .putString("vehicle_model", first.model)
                .putString("vehicle_id", first.id)
                .apply()
            first
        }
        return token to vehicle
    }

    companion object {
        private const val TAG = "KiaPebbleListener"
    }
}

/** Remembers the last watch that talked to us (unused in K2; kept for K3). */
object PebbleWatchStore {
    fun saveLastWatch(context: Context, watch: WatchIdentifier) {
        context.getSharedPreferences("kia_companion_settings", Context.MODE_PRIVATE)
            .edit().putString("last_watch", watch.value.toString()).apply()
    }

    fun lastWatch(context: Context): WatchIdentifier? {
        val value = context.getSharedPreferences("kia_companion_settings", Context.MODE_PRIVATE)
            .getString("last_watch", null) ?: return null
        return WatchIdentifier(value)
    }
}
