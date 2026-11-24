package com.mosfettlabs.kiacompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * K1 diagnostics UI: prove Kia login + TLS + status fetch on the device.
 * Everything is shown as plain text so failures are legible.
 */
class MainActivity : Activity() {
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var outputText: TextView
    private lateinit var otpInput: EditText
    private lateinit var otpButton: Button
    private lateinit var bridgeUrlInput: EditText
    private lateinit var bridgeTokenInput: EditText

    private val api = KiaApi()
    private var token: KiaApi.Token? = null
    private var vehicle: KiaApi.Vehicle? = null
    private var pendingOtp: KiaApi.OtpNeeded? = null
    private var pendingDeviceId: String? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("kia_companion_settings", MODE_PRIVATE)

        usernameInput = field("Kia Connect email")
        passwordInput = field("Password", password = true)
        pinInput = field("PIN (if your account uses one)", password = true)
        usernameInput.setText(prefs.getString("username", ""))
        pinInput.setText(prefs.getString("pin", ""))

        bridgeUrlInput = EditText(this).apply {
            hint = "Bridge URL (http://brick-ip:8777)"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 145, 170))
            background = rounded()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString("bridge_url", ""))
        }
        val bridgeButton = button("Status via bridge") { fetchStatusViaBridge() }
        bridgeTokenInput = EditText(this).apply {
            hint = "Bridge token"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 145, 170))
            background = rounded()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(prefs.getString("bridge_token", ""))
        }
        outputText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.rgb(190, 220, 240))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        otpInput = EditText(this).apply {
            hint = "6-digit OTP code"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 145, 170))
            background = rounded()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        otpButton = button("Verify OTP") {
            val otp = pendingOtp
            val deviceId = pendingDeviceId
            if (otp == null || deviceId == null) {
                outputText.text = "No OTP pending — login first.\n"
                return@button
            }
            outputText.text = "Verifying OTP…\n"
            thread {
                try {
                    val newToken = api.verifyOtpAndCompleteLogin(
                        usernameInput.text.toString().trim(),
                        passwordInput.text.toString(),
                        pinInput.text.toString().trim(),
                        deviceId,
                        otp,
                        otpInput.text.toString(),
                    )
                    token = newToken
                    prefs.edit().putString("device_id", newToken.deviceId).apply()
                    runOnUiThread {
                        otpInput.setText("")
                    }
                    pendingOtp = null
                    fetchAndShowStatus("OTP LOGIN OK (sid + device registered)\n")
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "FAILED: ${e.message}\n"
                    }
                }
            }
        }

        val loginButton = button("Login + Status") {
            savePrefs(prefs)
            outputText.text = "Logging in…\n"
            thread {
                try {
                    val stored = prefs.getString("device_id", null)
                    val result = api.login(
                        usernameInput.text.toString().trim(),
                        passwordInput.text.toString(),
                        pinInput.text.toString().trim(),
                        stored,
                    )
                    when (result) {
                        is KiaApi.Token -> {
                            token = result
                            if (stored == null) {
                                prefs.edit().putString("device_id", result.deviceId).apply()
                            }
                            pendingOtp = null
                            fetchAndShowStatus("LOGIN OK (sid valid 24h)\n")
                        }
                        is KiaApi.OtpNeeded -> {
                            pendingOtp = result
                            pendingDeviceId = stored ?: api.currentDeviceId()
                            api.sendOtp(result, if (result.hasPhone) "sms" else "email")
                            runOnUiThread {
                                outputText.text =
                                    "NEW DEVICE: Kia sent a 6-digit code to " +
                                    (if (result.hasPhone) "your phone (SMS)" else "your email") +
                                    ". Enter it in the OTP box and tap Verify.\n"
                                otpInput.visibility = android.view.View.VISIBLE
                                otpButton.visibility = android.view.View.VISIBLE
                            }
                        }
                        else -> throw IllegalStateException("unexpected login result")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "FAILED: ${e.message}\n"
                    }
                }
            }
        }


        val statusButton = button("Refresh status (cached)") {
            val t = token
            val v = vehicle
            if (t == null || v == null) {
                outputText.text = "Login first.\n"
                return@button
            }
            outputText.text = "Fetching cached status…\n"
            thread {
                try {
                    val status = api.getCachedStatus(t, v)
                    runOnUiThread {
                        outputText.text = buildString {
                            append("Vehicle: ${status.vehicle.name}\n")
                            append("Locked: ${status.locked}\n")
                            status.fuelLevelPercent?.let { append("Fuel level: $it%\n") }
                            status.lastUpdatedUtc?.let { append("Last updated (UTC): $it\n") }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "FAILED: ${e.message}\n"
                    }
                }
            }
        }

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(13, 27, 42))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(24), dp(18), dp(24))
                    addView(title("Kia Companion — K1 diagnostics"))
                    addView(spacer())
                    addView(label("Kia Connect email"))
                    addView(usernameInput)
                    addView(spacer())
                    addView(label("Password"))
                    addView(passwordInput)
                    addView(spacer())
                    addView(label("PIN (optional)"))
                    addView(pinInput)
                    addView(spacer())
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(loginButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(8)
                        })
                        addView(statusButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                    addView(spacer())
                    addView(label("OTP code"))
                    addView(otpInput)
                    addView(otpButton)
                    addView(spacer())
                    addView(label("Bridge mode (Brick PC, proven fingerprint)"))
                    addView(bridgeUrlInput)
                    addView(bridgeTokenInput)
                    addView(bridgeButton)
                    addView(spacer())
                    addView(label("Output"))
                    addView(outputText)
                },
            )
        }
        setContentView(root)
    }

    @SuppressLint("SetTextI18n")
    private fun fetchAndShowStatus(prefix: String) {
        val t = token
        if (t == null) {
            runOnUiThread { outputText.text = "Login first.\n" }
            return
        }
        thread {
            try {
                val vehicles = api.getVehicles(t)
                vehicle = vehicles.first()
                val status = api.getCachedStatus(t, vehicle!!)
                runOnUiThread {
                    outputText.text = buildString {
                        append(prefix)
                        append("Vehicles: ${vehicles.size}\n")
                        for (v in vehicles) {
                            append("- ${v.name} (${v.model})\n")
                        }
                        append("\nSTATUS\n")
                        append("Vehicle: ${status.vehicle.name}\n")
                        append("Locked: ${status.locked}\n")
                        status.fuelLevelPercent?.let { append("Fuel level: $it%\n") }
                        status.odometerMiles?.let { append("Odometer: ${it.toInt()} mi\n") }
                        status.lastUpdatedUtc?.let { append("Last updated (UTC): $it\n") }
                        append("\nK1 AUTH + STATUS PROVEN — ready for watch wiring.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    outputText.text = "FAILED: ${e.message}\n"
                }
            }
        }
    }

    private fun fetchStatusViaBridge() {
        val prefs = getSharedPreferences("kia_companion_settings", MODE_PRIVATE)
        prefs.edit()
            .putString("bridge_url", bridgeUrlInput.text.toString().trim())
            .putString("bridge_token", bridgeTokenInput.text.toString().trim())
            .apply()
        val url = bridgeUrlInput.text.toString().trim().trimEnd('/')
        val token = bridgeTokenInput.text.toString().trim()
        if (url.isBlank() || token.isBlank()) {
            outputText.text = "Bridge URL and token are required.\n"
            return
        }
        outputText.text = "Asking bridge for status…\n"
        thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("$url/status")
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                response.use {
                    val text = it.body?.string().orEmpty()
                    val json = JSONObject(text)
                    if (!json.optBoolean("ok", false)) {
                        runOnUiThread { outputText.text = "BRIDGE ERROR: ${json.optString("error", text.take(200))}\n" }
                        return@use
                    }
                    val vehicles = json.optJSONArray("vehicles")
                    val out = StringBuilder("BRIDGE OK\n")
                    if (vehicles != null) {
                        for (i in 0 until vehicles.length()) {
                            val v = vehicles.optJSONObject(i) ?: continue
                            out.append("- ${v.optString("name")} (${v.optString("model")})\n")
                            out.append("  locked: ${v.optBoolean("locked", true)}\n")
                            v.opt("fuel_level_pct")?.let { pct -> out.append("  fuel level: $pct\n") }
                            v.opt("odometer_miles")?.let { mi ->
                                val miles = when (mi) {
                                    is Number -> mi.toDouble()
                                    is String -> mi.toDoubleOrNull()
                                    else -> null
                                }
                                miles?.let { m -> out.append("  odometer: ${Math.round(m)} mi\n") }
                            }
                            v.optString("last_updated").takeIf { it.isNotBlank() }?.let { out.append("  last updated: $it\n") }
                        }
                    }
                    out.append("\nBRIDGE STATUS PROVEN — K2 (watch UI) next.")
                    runOnUiThread { outputText.text = out.toString() }
                }
            } catch (e: Exception) {
                runOnUiThread { outputText.text = "BRIDGE FAILED: ${e.message}\n" }
            }
        }
    }

    private fun savePrefs(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putString("username", usernameInput.text.toString().trim())
            .putString("pin", pinInput.text.toString().trim())
            .apply()
    }

    private fun field(hint: String, password: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            textSize = 15f
            isSingleLine = true
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 145, 170))
            background = rounded()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun button(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(11, 87, 164))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(127, 195, 255))
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(160, 180, 200))
        }
    }

    private fun spacer(): android.view.View {
        return android.view.View(this).also { it.minimumHeight = dp(14) }
    }

    private fun rounded(fill: Int = Color.rgb(23, 41, 63)): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(45, 70, 100))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
