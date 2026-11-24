package com.mosfettlabs.kiacompanion

import android.app.Activity
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kia Connect USA client (port of hyundai_kia_connect_api's KiaUvoApiUSA,
 * Apache-2.0) driven through the WebView transport, because Kia USA sits
 * behind Cloudflare which scores TLS fingerprints and only trusts browser /
 * reference stacks. Reference vendored at vendor/hyundai_kia_connect_api.
 */
class KiaApi(activity: Activity) {
    data class Token(
        val username: String,
        val password: String,
        val pin: String,
        val deviceId: String,
        val sid: String,
        val validUntilMs: Long,
    )

    data class Vehicle(
        val id: String,
        val name: String,
        val model: String,
        val key: String,
    )

    data class VehicleStatus(
        val vehicle: Vehicle,
        val locked: Boolean,
        val fuelLevelPercent: Int?,
        val odometerMiles: Double?,
        val lastUpdatedUtc: String?,
    )

    data class OtpNeeded(
        val otpKey: String,
        val requestId: String,
        val hasEmail: Boolean,
        val hasPhone: Boolean,
    )

    data class ApiError(override val message: String) : Exception(message)

    private var currentDeviceId: String? = null
    private val transport = KiaWebViewTransport(activity)

    private class RawResponse(val status: Int, val text: String, val headers: Map<String, String>)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): RawResponse {
        val response = transport.execute(url, method, headers, body)
        val headerMap = parseResponseHeaders(response.headers)
        return RawResponse(response.status, response.text, headerMap)
    }

    private fun parseResponseHeaders(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        raw.split("\n", "\r").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    private fun postJson(path: String, headers: Map<String, String>, body: String): RawResponse {
        val response = request("POST", "$API_URL/$path", headers, body)
        if (response.status in 500..599) {
            throw ApiError("Kia returned HTTP ${response.status} (server-side); try again later.")
        }
        if (response.status == 403) {
            throw ApiError("Kia blocked the request (HTTP 403). Response: ${response.text.take(160)}")
        }
        return response
    }

    private fun get(path: String, headers: Map<String, String>): RawResponse {
        val response = request("GET", "$API_URL/$path", headers, null)
        if (response.status in 500..599) {
            throw ApiError("Kia returned HTTP ${response.status} (server-side); try again later.")
        }
        if (response.status == 403) {
            throw ApiError("Kia blocked the request (HTTP 403). Response: ${response.text.take(160)}")
        }
        return response
    }

    fun apiHeaders(deviceId: String): MutableMap<String, String> {
        val clientUuid = UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
        val offsetHours = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000
        return mutableMapOf(
            "content-type" to "application/json;charset=utf-8",
            "accept" to "application/json",
            "accept-language" to "en-US,en;q=0.9",
            "apptype" to "L",
            "appversion" to "7.22.0",
            "clientid" to "SPACL716-APL",
            "clientuuid" to clientUuid,
            "from" to "SPA",
            "language" to "0",
            "offset" to offsetHours.toString(),
            "ostype" to "iOS",
            "osversion" to "15.8.5",
            "phonebrand" to "iPhone",
            "secretkey" to SECRET_KEY,
            "to" to "APIGW",
            "tokentype" to "A",
            "user-agent" to "KIAPrimo_iOS/37 CFNetwork/1335.0.3.4 Darwin/21.6.0",
            "deviceid" to deviceId,
        )
    }

    private fun authedHeaders(token: Token, vehicleKey: String?): Map<String, String> {
        val headers = apiHeaders(token.deviceId)
        headers["sid"] = token.sid
        if (vehicleKey != null) {
            headers["vinkey"] = vehicleKey
        }
        return headers
    }

    fun login(username: String, password: String, pin: String, storedDeviceId: String?): Any {
        if (username.isBlank() || password.isBlank()) {
            throw ApiError("Username and password are required.")
        }
        val deviceId = storedDeviceId ?: UUID.randomUUID().toString().uppercase()
        currentDeviceId = deviceId
        val body = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
            .put("tncFlag", 1)
        val response = postJson("prof/authUser", apiHeaders(deviceId), body.toString())
        val sid = response.headers["sid"]
        if (!sid.isNullOrBlank()) {
            return Token(
                username = username,
                password = password,
                pin = pin,
                deviceId = deviceId,
                sid = sid,
                validUntilMs = System.currentTimeMillis() + LOGIN_TOKEN_LIFETIME_MS,
            )
        }
        val json = try {
            JSONObject(response.text)
        } catch (_: Exception) {
            JSONObject()
        }
        val payload = json.optJSONObject("payload")
        if (payload != null && payload.has("otpKey")) {
            return OtpNeeded(
                otpKey = payload.optString("otpKey"),
                requestId = response.headers["xid"].orEmpty(),
                hasEmail = payload.optBoolean("hasEmail", false),
                hasPhone = payload.optBoolean("hasPhone", false),
            )
        }
        val status = json.optJSONObject("status")
        if (status != null && status.optInt("errorCode", -1) == 1001) {
            throw ApiError("Invalid email or password.")
        }
        throw ApiError("Login failed (HTTP ${response.status}). Response: ${response.text.take(160)}")
    }

    fun currentDeviceId(): String? = currentDeviceId

    fun sendOtp(otp: OtpNeeded, notifyType: String) {
        val deviceId = currentDeviceId ?: throw ApiError("Log in first.")
        val headers = apiHeaders(deviceId)
        headers["otpkey"] = otp.otpKey
        headers["notifytype"] = notifyType
        headers["xid"] = otp.requestId
        val response = postJson("cmm/sendOTP", headers, JSONObject().toString())
        try {
            JSONObject(response.text)
        } catch (_: Exception) {
            throw ApiError("Send OTP failed: ${response.text.take(120)}")
        }
    }

    fun verifyOtpAndCompleteLogin(
        username: String,
        password: String,
        pin: String,
        deviceId: String,
        otp: OtpNeeded,
        otpCode: String,
    ): Token {
        val headers = apiHeaders(deviceId)
        headers["otpkey"] = otp.otpKey
        headers["xid"] = otp.requestId
        val verifyResponse = postJson(
            "cmm/verifyOTP",
            headers,
            JSONObject().put("otp", otpCode.trim()).toString(),
        )
        val sid = verifyResponse.headers["sid"]
        val rmtoken = verifyResponse.headers["rmtoken"]
        if (sid.isNullOrBlank() || rmtoken.isNullOrBlank()) {
            val status = try {
                JSONObject(verifyResponse.text).optJSONObject("status")
            } catch (_: Exception) {
                null
            }
            throw ApiError(
                "OTP verification failed: ${status?.optString("errorMessage")
                    .takeUnless { m -> m.isNullOrBlank() }
                    ?: "no session in response (HTTP ${verifyResponse.status})"}",
            )
        }
        val completeHeaders = apiHeaders(deviceId)
        completeHeaders["sid"] = sid
        completeHeaders["rmtoken"] = rmtoken
        val completeBody = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
        val completeResponse = postJson("prof/authUser", completeHeaders, completeBody.toString())
        val finalSid = completeResponse.headers["sid"]
        if (finalSid.isNullOrBlank()) {
            throw ApiError("Login completion failed (no final sid). Response: ${completeResponse.text.take(160)}")
        }
        return Token(
            username = username,
            password = password,
            pin = pin,
            deviceId = deviceId,
            sid = finalSid,
            validUntilMs = System.currentTimeMillis() + LOGIN_TOKEN_LIFETIME_MS,
        )
    }

    fun getVehicles(token: Token): List<Vehicle> {
        val response = get("ownr/gvl", authedHeaders(token, null))
        val json = try {
            JSONObject(response.text)
        } catch (_: Exception) {
            throw ApiError("Vehicle list failed with unparseable response (HTTP ${response.status}).")
        }
        checkAuthStatus(json)
        val summary = json.optJSONObject("payload")?.optJSONArray("vehicleSummary")
            ?: throw ApiError("Vehicle list: missing payload.vehicleSummary.")
        val vehicles = mutableListOf<Vehicle>()
        for (i in 0 until summary.length()) {
            val entry = summary.optJSONObject(i) ?: continue
            vehicles.add(
                Vehicle(
                    id = entry.optString("vehicleIdentifier"),
                    name = entry.optString("nickName").ifBlank { entry.optString("modelName") },
                    model = entry.optString("modelName"),
                    key = entry.optString("vehicleKey"),
                ),
            )
        }
        if (vehicles.isEmpty()) {
            throw ApiError("No vehicles on this Kia account.")
        }
        return vehicles
    }

    fun getCachedStatus(token: Token, vehicle: Vehicle): VehicleStatus {
        val body = JSONObject()
            .put(
                "vehicleConfigReq",
                JSONObject()
                    .put("airTempRange", "0")
                    .put("maintenance", "1")
                    .put("seatHeatCoolOption", "0")
                    .put("vehicle", "1")
                    .put("vehicleFeature", "0"),
            )
            .put(
                "vehicleInfoReq",
                JSONObject()
                    .put("drivingActivty", "0")
                    .put("dtc", "1")
                    .put("enrollment", "1")
                    .put("functionalCards", "0")
                    .put("location", "1")
                    .put("vehicleStatus", "1")
                    .put("weather", "0"),
            )
            .put("vinKey", JSONArray().put(vehicle.key))
        val response = postJson("cmm/gvi", authedHeaders(token, vehicle.key), body.toString())
        val json = try {
            JSONObject(response.text)
        } catch (_: Exception) {
            throw ApiError("Status failed with unparseable response (HTTP ${response.status}).")
        }
        checkAuthStatus(json)
        val info = json.optJSONObject("payload")?.optJSONArray("vehicleInfoList")?.optJSONObject(0)
            ?: throw ApiError("Status: missing payload.vehicleInfoList[0].")
        return parseStatus(vehicle, info)
    }

    private fun parseStatus(vehicle: Vehicle, info: JSONObject): VehicleStatus {
        val status = firstObject(info, "lastVehicleInfo", "vehicleStatusRpt", "vehicleStatus")
        val doorLock = status?.opt("doorLock")
        val locked = when (doorLock) {
            is Boolean -> doorLock
            is Int -> doorLock == 0
            else -> true
        }
        val fuelLevel = status?.opt("fuelLevel")?.let { toDoubleOrNull(it) }?.toInt()
        val odometer = info.optJSONObject("vehicleConfig")
            ?.optJSONObject("vehicleDetail")
            ?.optJSONObject("vehicle")
            ?.opt("mileage")
            ?.let { toDoubleOrNull(it) }
        val lastUpdated = status?.optJSONObject("syncDate")?.let { sync ->
            val utc = sync.optJSONObject("utc")
            val ms = utc?.optString("ms")?.takeIf { it.isNotBlank() }?.toLongOrNull()
                ?: (utc?.opt("ms") as? Number)?.toLong()
            if (ms != null && ms > 0) {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(ms))
            } else {
                utc?.optString("string")?.takeIf { it.isNotBlank() }
            }
        }
        return VehicleStatus(
            vehicle = vehicle,
            locked = locked,
            fuelLevelPercent = fuelLevel,
            odometerMiles = odometer,
            lastUpdatedUtc = lastUpdated,
        )
    }

    private fun firstObject(root: JSONObject, vararg path: String): JSONObject? {
        var cursor: JSONObject? = root
        for (key in path) {
            cursor = cursor?.optJSONObject(key) ?: return null
        }
        return cursor
    }

    private fun toDoubleOrNull(value: Any): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun checkAuthStatus(json: JSONObject) {
        val status = json.optJSONObject("status") ?: return
        if (status.optInt("statusCode", 0) == 1 &&
            status.optInt("errorType", 0) == 1 &&
            status.optInt("errorCode", 0) in intArrayOf(1003, 1005)
        ) {
            throw ApiError("Kia session invalid — log in again.")
        }
    }

    fun shutdown() {
        transport.persistCookies()
        transport.shutdown()
    }

    companion object {
        private const val BASE_URL = "api.owners.kia.com"
        private const val API_URL = "https://$BASE_URL/apigw/v1"
        private const val SECRET_KEY = "sydnat-9kykci-Kuhtep-h5nK"
        private const val LOGIN_TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}
