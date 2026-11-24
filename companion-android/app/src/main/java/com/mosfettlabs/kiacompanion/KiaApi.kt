package com.mosfettlabs.kiacompanion

import java.util.UUID
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Port of hyundai_kia_connect_api's KiaUvoApiUSA (v5.4.1, Apache-2.0) to Kotlin
 * for Kia USA (api.owners.kia.com/apigw/v1/). Reference vendored at
 * vendor/hyundai_kia_connect_api @ acd9ea5.
 */
class KiaApi {
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

    data class ApiError(override val message: String) : Exception(message)

    private var currentDeviceId: String? = null

    private val client: OkHttpClient by lazy {
        // Kia USA rejects modern cipher defaults; mirror the reference's
        // SECLEVEL=1 TLSv1.2 adapter with a permissive ConnectionSpec.
        val legacySpec = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .cipherSuites(
                "TLS_RSA_WITH_AES_128_CBC_SHA",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            )
            .build()
        OkHttpClient.Builder()
            .connectionSpecs(listOf(legacySpec, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
            // The reference stack (python-requests) speaks HTTP/1.1 only; Kia's
            // WAF appears sensitive to the protocol and header fingerprint.
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(TransparentGzipInterceptor())
            .addInterceptor(ErrorInterceptor())
            .build()
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

    /** Transparently decodes compressed bodies (OkHttp skips auto-gzip when
     *  the caller sets accept-encoding itself, which we do to match the
     *  reference fingerprint). */
    private class TransparentGzipInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val encoding = response.header("Content-Encoding")?.lowercase(Locale.US)
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

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    fun apiHeaders(deviceId: String): MutableMap<String, String> {
        val clientUuid = UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
        val offsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000
        val gmtDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        return mutableMapOf(
            "content-type" to "application/json;charset=utf-8",
            "accept" to "application/json",
            "accept-encoding" to "gzip, deflate, br",
            "accept-language" to "en-US,en;q=0.9",
            "accept-charset" to "utf-8",
            "apptype" to "L",
            "appversion" to "7.22.0",
            "clientid" to "SPACL716-APL",
            "clientuuid" to clientUuid,
            "from" to "SPA",
            "host" to BASE_URL,
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

    data class OtpNeeded(
        val otpKey: String,
        val requestId: String,
        val hasEmail: Boolean,
        val hasPhone: Boolean,
    )

    fun login(username: String, password: String, pin: String, storedDeviceId: String?): Any {
        if (username.isBlank() || password.isBlank()) {
            throw ApiError("Username and password are required.")
        }
        val deviceId = storedDeviceId ?: UUID.randomUUID().toString().uppercase(Locale.US)
        currentDeviceId = deviceId
        val body = JSONObject()
            .put("deviceKey", deviceId)
            .put("deviceType", 2)
            .put("userCredential", JSONObject().put("userId", username).put("password", password))
            .put("tncFlag", 1)
        val request = Request.Builder()
            .url("$API_URL/prof/authUser")
            .headers(buildHeaders(apiHeaders(deviceId)))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            val sid = it.header("sid")
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
                    requestId = it.header("xid").orEmpty(),
                    hasEmail = payload.optBoolean("hasEmail", false),
                    hasPhone = payload.optBoolean("hasPhone", false),
                )
            }
            val status = json.optJSONObject("status")
            if (status != null && status.optInt("errorCode", -1) == 1001) {
                throw ApiError("Invalid email or password.")
            }
            throw ApiError("Login failed (HTTP ${it.code}). Response: ${text.take(160)}")
        }
    }

    fun sendOtp(otp: OtpNeeded, notifyType: String) {
        val headers = apiHeaders(currentDeviceId ?: throw ApiError("Log in first."))
        headers["otpkey"] = otp.otpKey
        headers["notifytype"] = notifyType
        headers["xid"] = otp.requestId
        val request = Request.Builder()
            .url("$API_URL/cmm/sendOTP")
            .headers(buildHeaders(headers))
            .post(JSONObject().toString().toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.code !in 200..299) {
                throw ApiError("Send OTP failed (HTTP ${it.code}): ${text.take(120)}")
            }
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
        val verifyRequest = Request.Builder()
            .url("$API_URL/cmm/verifyOTP")
            .headers(buildHeaders(headers))
            .post(JSONObject().put("otp", otpCode.trim()).toString().toRequestBody(jsonType))
            .build()
        val verifyResponse = client.newCall(verifyRequest).execute()
        verifyResponse.use {
            val text = it.body?.string().orEmpty()
            val sid = it.header("sid")
            val rmtoken = it.header("rmtoken")
            if (sid.isNullOrBlank() || rmtoken.isNullOrBlank()) {
                val status = try {
                    JSONObject(text).optJSONObject("status")
                } catch (_: Exception) {
                    null
                }
                throw ApiError(
                    "OTP verification failed: ${status?.optString("errorMessage")
                        .takeUnless { m -> m.isNullOrBlank() } ?: "no session in response (HTTP ${it.code})"}",
                )
            }
            val completeHeaders = apiHeaders(deviceId)
            completeHeaders["sid"] = sid
            completeHeaders["rmtoken"] = rmtoken
            val completeBody = JSONObject()
                .put("deviceKey", deviceId)
                .put("deviceType", 2)
                .put("userCredential", JSONObject().put("userId", username).put("password", password))
            val completeRequest = Request.Builder()
                .url("$API_URL/prof/authUser")
                .headers(buildHeaders(completeHeaders))
                .post(completeBody.toString().toRequestBody(jsonType))
                .build()
            val completeResponse = client.newCall(completeRequest).execute()
            completeResponse.use { complete ->
                val finalSid = complete.header("sid")
                if (finalSid.isNullOrBlank()) {
                    throw ApiError("Login completion failed (no final sid). Response: ${complete.body?.string().orEmpty().take(160)}")
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
        }
    }

    fun getVehicles(token: Token): List<Vehicle> {
        val request = Request.Builder()
            .url("$API_URL/ownr/gvl")
            .headers(buildHeaders(authedHeaders(token, null)))
            .get()
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw ApiError("Vehicle list failed with unparseable response (HTTP ${it.code}).")
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
        val request = Request.Builder()
            .url("$API_URL/cmm/gvi")
            .headers(buildHeaders(authedHeaders(token, vehicle.key)))
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw ApiError("Status failed with unparseable response (HTTP ${it.code}).")
            }
            checkAuthStatus(json)
            val info = json.optJSONObject("payload")?.optJSONArray("vehicleInfoList")?.optJSONObject(0)
                ?: throw ApiError("Status: missing payload.vehicleInfoList[0].")
            return parseStatus(vehicle, info)
        }
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
                ?: utc?.optString("ms")?.takeIf { it.isNotBlank() }?.let { try { it.toDouble().toLong() } catch (_: Exception) { null } }
            if (ms != null && ms > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(ms))
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

    private fun buildHeaders(headers: Map<String, String>): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        for ((name, value) in headers) {
            builder.add(name, value)
        }
        return builder.build()
    }

    fun currentDeviceId(): String? = currentDeviceId

    companion object {
        private const val BASE_URL = "api.owners.kia.com"
        private const val API_URL = "https://$BASE_URL/apigw/v1/"
        private const val SECRET_KEY = "sydnat-9kykci-Kuhtep-h5nK"
        private const val LOGIN_TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000
    }
}
