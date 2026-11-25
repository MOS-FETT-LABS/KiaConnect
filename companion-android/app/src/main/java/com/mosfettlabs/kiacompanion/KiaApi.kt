package com.mosfettlabs.kiacompanion

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kia Connect USA client (port of hyundai_kia_connect_api's KiaUvoApiUSA,
 * Apache-2.0). Kia USA sits behind Cloudflare; the working fingerprint
 * (proven from a JVM with tools/KiaTlsProbe.java) is: TLS 1.2 with the
 * legacy cipher list, HTTP/1.1, the full reference header set INCLUDING
 * `date`, and NO duplicate Host header.
 */
class KiaApi {
    data class Token(
        val username: String,
        val password: String,
        val pin: String,
        val deviceId: String,
        val sid: String,
        val validUntilMs: Long,
        val rmtoken: String? = null,
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
        val airTempF: Int? = null,
    )

    data class OtpNeeded(
        val otpKey: String,
        val requestId: String,
        val hasEmail: Boolean,
        val hasPhone: Boolean,
    )

    data class ApiError(override val message: String) : Exception(message)

    private var currentDeviceId: String? = null

    private class TransparentGzipInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val encoding = response.header("Content-Encoding")?.lowercase()
            if (encoding != "gzip" && encoding != "deflate") {
                return response
            }
            val raw = response.body?.source()?.use { it.readByteArray() } ?: ByteArray(0)
            val text = try {
                val stream = if (encoding == "gzip") {
                    java.util.zip.GZIPInputStream(raw.inputStream())
                } else {
                    java.util.zip.InflaterInputStream(raw.inputStream())
                }
                stream.use { it.readBytes().decodeToString() }
            } catch (_: Exception) {
                raw.decodeToString()
            }
            return response.newBuilder()
                .body(okhttp3.ResponseBody.create(
                    response.body?.contentType() ?: "application/json".toMediaType(),
                    text,
                ))
                .build()
        }
    }

    private class ErrorInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (response.code in 500..599) {
                throw ApiError("Kia returned HTTP ${response.code} (server-side); try again later.")
            }
            return response
        }
    }

    private val client: OkHttpClient by lazy {
        val legacySpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .cipherSuites(
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            )
            .build()
        OkHttpClient.Builder()
            .connectionSpecs(listOf(legacySpec, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .cookieJar(object : okhttp3.CookieJar {
                private val store = mutableMapOf<String, List<okhttp3.Cookie>>()
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    store[url.host] = cookies
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    return store[url.host].orEmpty()
                }
            })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(TransparentGzipInterceptor())
            .addInterceptor(ErrorInterceptor())
            .build()
    }

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    fun apiHeaders(deviceId: String): MutableMap<String, String> {
        val clientUuid = UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
        val offsetHours = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000
        val gmtDate = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
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
            "date" to gmtDate,
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

    private fun parseResponseHeaders(raw: okhttp3.Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (name in raw.names()) {
            map[name.lowercase()] = raw[name].orEmpty()
        }
        return map
    }

    private fun execute(request: Request): Pair<Int, Pair<String, Map<String, String>>> {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code == 403 && text.contains("<")) {
                throw ApiError("Kia blocked the request (HTTP 403). Response: ${text.take(160)}")
            }
            return it.code to Pair(text, parseResponseHeaders(it.headers))
        }
    }

    private fun postJson(path: String, headers: Map<String, String>, body: String): Pair<String, Map<String, String>> {
        val request = Request.Builder()
            .url("$API_URL/$path")
            .headers(buildHeaders(headers))
            .post(body.toRequestBody(jsonType))
            .build()
        val (code, pair) = execute(request)
        if (code in 500..599) {
            throw ApiError("Kia returned HTTP $code (server-side); try again later.")
        }
        return pair
    }

    private fun get(path: String, headers: Map<String, String>): Pair<String, Map<String, String>> {
        val request = Request.Builder()
            .url("$API_URL/$path")
            .headers(buildHeaders(headers))
            .get()
            .build()
        val (code, pair) = execute(request)
        if (code in 500..599) {
            throw ApiError("Kia returned HTTP $code (server-side); try again later.")
        }
        return pair
    }

    fun login(username: String, password: String, pin: String, storedDeviceId: String?, storedRmtoken: String? = null, storedSid: String? = null): Any {
        if (username.isBlank() || password.isBlank()) {
            throw ApiError("Username and password are required.")
        }
        val deviceId = storedDeviceId ?: UUID.randomUUID().toString().uppercase()
        currentDeviceId = deviceId
        if (!storedRmtoken.isNullOrBlank()) {
            try {
                return refreshWithRmtoken(username, password, pin, deviceId, storedRmtoken, storedSid)
            } catch (_: ApiError) {
                // fall through to the full MFA flow (rmtoken expired or device changed)
            }
        }
        val body = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
            .put("tncFlag", 1)
        val (text, headers) = postJson("prof/authUser", apiHeaders(deviceId), body.toString())
        val sid = headers["sid"]
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
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
        val payload = json.optJSONObject("payload")
        if (payload != null && payload.has("otpKey")) {
            return OtpNeeded(
                otpKey = payload.optString("otpKey"),
                requestId = headers["xid"].orEmpty(),
                hasEmail = payload.optBoolean("hasEmail", false),
                hasPhone = payload.optBoolean("hasPhone", false),
            )
        }
        val status = json.optJSONObject("status")
        if (status != null && status.optInt("errorCode", -1) == 1001) {
            throw ApiError("Invalid email or password.")
        }
        throw ApiError("Login failed (HTTP 200). Response: ${text.take(160)}")
    }

    fun currentDeviceId(): String? = currentDeviceId

    /** Step 4: mint a fresh sid from the persisted sid+rmtoken (no MFA).
     *  Shape matches the combination proven working on-device (K1). */
    private fun refreshWithRmtoken(
        username: String,
        password: String,
        pin: String,
        deviceId: String,
        rmtoken: String,
        storedSid: String? = null,
    ): Token {
        val headers = apiHeaders(deviceId)
        storedSid?.takeIf { it.isNotBlank() }?.let { headers["sid"] = it }
        headers["rmtoken"] = rmtoken
        val body = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
        val (text, outHeaders) = postJson("prof/authUser", headers, body.toString())
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
        val payload = json.optJSONObject("payload")
        if (payload != null && payload.has("otpKey")) {
            throw ApiError("rmtoken expired — full MFA flow needed.")
        }
        val sid = outHeaders["sid"]
        if (sid.isNullOrBlank()) {
            throw ApiError("Refresh returned no sid: ${text.take(140)}")
        }
        return Token(
            username = username,
            password = password,
            pin = pin,
            deviceId = deviceId,
            sid = sid,
            validUntilMs = System.currentTimeMillis() + LOGIN_TOKEN_LIFETIME_MS,
            rmtoken = outHeaders["rmtoken"] ?: rmtoken,
        )
    }

    fun sendOtp(otp: OtpNeeded, notifyType: String) {
        val deviceId = currentDeviceId ?: throw ApiError("Log in first.")
        val headers = apiHeaders(deviceId)
        headers["otpkey"] = otp.otpKey
        headers["notifytype"] = notifyType.uppercase()
        headers["xid"] = otp.requestId
        val (text, _) = postJson("cmm/sendOTP", headers, JSONObject().toString())
        try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApiError("Send OTP failed: ${text.take(120)}")
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
        val (verifyText, verifyHeaders) = postJson(
            "cmm/verifyOTP",
            headers,
            JSONObject().put("otp", otpCode.trim()).toString(),
        )
        val sid = verifyHeaders["sid"]
        val rmtoken = verifyHeaders["rmtoken"]
        if (sid.isNullOrBlank() || rmtoken.isNullOrBlank()) {
            val status = try {
                JSONObject(verifyText).optJSONObject("status")
            } catch (_: Exception) {
                null
            }
            throw ApiError(
                "OTP verification failed: ${status?.optString("errorMessage")
                    .takeUnless { m -> m.isNullOrBlank() }
                    ?: "no session in response (HTTP 200)"}",
            )
        }
        val completeHeaders = apiHeaders(deviceId)
        completeHeaders["sid"] = sid
        completeHeaders["rmtoken"] = rmtoken
        val completeBody = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
        val (completeText, completeHeadersOut) = postJson("prof/authUser", completeHeaders, completeBody.toString())
        val finalSid = completeHeadersOut["sid"]
        if (finalSid.isNullOrBlank()) {
            throw ApiError("Login completion failed (no final sid). Response: ${completeText.take(160)}")
        }
        return Token(
            username = username,
            password = password,
            pin = pin,
            deviceId = deviceId,
            sid = finalSid,
            validUntilMs = System.currentTimeMillis() + LOGIN_TOKEN_LIFETIME_MS,
            rmtoken = rmtoken,
        )
    }

    fun getVehicles(token: Token): List<Vehicle> {
        val (text, _) = get("ownr/gvl", authedHeaders(token, null))
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApiError("Vehicle list failed with unparseable response.")
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
        val (text, _) = postJson("cmm/gvi", authedHeaders(token, vehicle.key), body.toString())
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApiError("Status failed with unparseable response.")
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
        val airTemp = status?.optJSONObject("climate")
            ?.optJSONObject("airTemp")
            ?.opt("value")
            ?.let { toDoubleOrNull(it) }
            ?.toInt()
        return VehicleStatus(
            vehicle = vehicle,
            locked = locked,
            fuelLevelPercent = fuelLevel,
            odometerMiles = odometer,
            lastUpdatedUtc = lastUpdated,
            airTempF = airTemp,
        )
    }

    enum class OrderStatus { SUCCESS, FAILED, PENDING, TIMEOUT }

    fun lockAction(token: Token, vehicle: Vehicle, lock: Boolean): String {
        val path = if (lock) "rems/door/lock" else "rems/door/unlock"
        val (_, headers) = get(path, authedHeaders(token, vehicle.key))
        val xid = headers["xid"]
        if (xid.isNullOrBlank()) throw ApiError("Lock action did not return a transaction id.")
        return xid
    }

    fun startClimate(token: Token, vehicle: Vehicle, tempValue: String, airCtrl: Boolean, durationMin: Int): String {
        val body = JSONObject()
            .put(
                "remoteClimate",
                JSONObject()
                    .put("airTemp", JSONObject().put("unit", 1).put("value", tempValue))
                    .put("airCtrl", airCtrl)
                    .put("defrost", false)
                    .put(
                        "heatingAccessory",
                        JSONObject()
                            .put("rearWindow", 0)
                            .put("sideMirror", 0)
                            .put("steeringWheel", 0)
                            .put("steeringWheelStep", 0),
                    )
                    .put("ignitionOnDuration", JSONObject().put("unit", 4).put("value", durationMin)),
            )
        val (_, headers) = postJson("rems/start", authedHeaders(token, vehicle.key), body.toString())
        val xid = headers["xid"]
        if (xid.isNullOrBlank()) throw ApiError("Climate start did not return a transaction id.")
        return xid
    }

    fun stopClimate(token: Token, vehicle: Vehicle): String {
        val (_, headers) = get("rems/stop", authedHeaders(token, vehicle.key))
        val xid = headers["xid"]
        if (xid.isNullOrBlank()) throw ApiError("Climate stop did not return a transaction id.")
        return xid
    }

    fun checkActionStatus(token: Token, vehicle: Vehicle, actionId: String, timeoutSeconds: Int): OrderStatus {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var lastJson: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            val (_, _) = Unit to Unit
            val response = postJson(
                "cmm/gts",
                authedHeaders(token, vehicle.key),
                JSONObject().put("xid", actionId).toString(),
            )
            val json = try {
                JSONObject(response.first)
            } catch (_: Exception) {
                throw ApiError("Transaction status unparseable: ${response.first.take(120)}")
            }
            val payload = json.optJSONObject("payload") ?: JSONObject()
            var allZero = payload.length() > 0
            val keys = payload.keys()
            while (keys.hasNext()) {
                val v = payload.opt(keys.next())
                if ((v as? Number)?.toInt() != 0) allZero = false
            }
            if (payload.length() > 0 && allZero) return OrderStatus.SUCCESS
            val status = json.optJSONObject("status")
            if (status != null && status.optInt("errorCode", -1) == 9040) {
                return OrderStatus.FAILED
            }
            lastJson = json
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
        return OrderStatus.TIMEOUT
    }

    fun forceRefresh(token: Token, vehicle: Vehicle): String {
        val (_, headers) = postJson(
            "rems/rvs",
            authedHeaders(token, vehicle.key),
            JSONObject().put("requestType", 0).toString(),
        )
        val xid = headers["xid"]
        if (xid.isNullOrBlank()) throw ApiError("Force refresh did not return a transaction id.")
        return xid
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

    private fun buildHeaders(headers: Map<String, String>): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        for ((name, value) in headers) {
            builder.add(name, value)
        }
        return builder.build()
    }

    companion object {
        private const val BASE_URL = "api.owners.kia.com"
        private const val API_URL = "https://$BASE_URL/apigw/v1"
        private const val SECRET_KEY = "sydnat-9kykci-Kuhtep-h5nK"
        private const val LOGIN_TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}
