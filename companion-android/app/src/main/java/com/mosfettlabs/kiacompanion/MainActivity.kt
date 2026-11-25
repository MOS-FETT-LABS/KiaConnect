package com.mosfettlabs.kiacompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mosfettlabs.kiacompanion.pebble.PebbleWatchStore
import com.mosfettlabs.kiacompanion.pebble.ServiceLog
import kotlin.concurrent.thread

/**
 * Kia Companion UI: login wizard (credentials → OTP choice popup → OTP) →
 * status screen with car info, output, service log, and a Pebble connection
 * indicator. Output and log are selectable text (long-press).
 */
class MainActivity : Activity() {
    private val api = KiaApi()
    private var token: KiaApi.Token? = null
    private var vehicle: KiaApi.Vehicle? = null
    private var pendingOtp: KiaApi.OtpNeeded? = null
    private var pendingDeviceId: String? = null
    private var pendingNotifyType: String? = null

    private lateinit var rootScroll: ScrollView
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var indicatorRunnable: Runnable? = null

    private val colorText = Color.rgb(233, 240, 246)
    private val colorMuted = Color.rgb(140, 165, 190)
    private val colorBg = Color.rgb(13, 27, 42)
    private val colorField = Color.rgb(23, 41, 63)
    private val colorStroke = Color.rgb(45, 70, 100)
    private val colorAccent = Color.rgb(11, 87, 164)
    private val colorGreen = Color.rgb(55, 178, 77)
    private val colorRed = Color.rgb(214, 69, 69)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootScroll = ScrollView(this).apply {
            setBackgroundColor(colorBg)
        }
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }
        rootScroll.addView(rootLayout)
        setContentView(rootScroll)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val sid = prefs.getString("sid", null)
        val expiry = prefs.getLong("sid_expiry", 0)
        val username = prefs.getString("username", "").orEmpty()
        if (!sid.isNullOrBlank() && expiry > System.currentTimeMillis() && username.isNotBlank()) {
            showStatusScreen()
        } else {
            showLoginScreen()
        }
    }

    override fun onDestroy() {
        indicatorRunnable?.let { uiHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun clearContent() {
        rootLayout.removeAllViews()
    }

    // ---------- login screen -------------------------------------------------

    private fun showLoginScreen() {
        clearContent()
        addView(title("Kia Companion"))
        addView(subtitle("Sign in to Kia Connect"))

        val usernameInput = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        usernameInput.setText(getPrefs().getString("username", ""))
        val passwordInput = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, imeDone = true)
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doLogin(usernameInput, passwordInput)
                true
            } else {
                false
            }
        }

        addView(label("Email"))
        addView(usernameInput)
        addView(spacer())
        addView(label("Password"))
        addView(passwordInput)
        addView(spacer())
        addView(button("Login") { doLogin(usernameInput, passwordInput) })
        addView(spacer())
        statusText = outputLine()
        addView(statusText)
    }

    private fun doLogin(usernameInput: EditText, passwordInput: EditText) {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (username.isBlank() || password.isBlank()) {
            statusText.text = "Email and password are required."
            return
        }
        statusText.text = "Logging in…"
        thread {
            try {
                val result = api.login(
                    username,
                    password,
                    getPrefs().getString("pin", "").orEmpty(),
                    getPrefs().getString("device_id", null),
                )
                when (result) {
                    is KiaApi.Token -> {
                        persistSession(result)
                        pendingOtp = null
                        runOnUiThread { showStatusScreen(autoFetch = true) }
                    }
                    is KiaApi.OtpNeeded -> {
                        pendingOtp = result
                        pendingDeviceId = getPrefs().getString("device_id", null) ?: api.currentDeviceId()
                        runOnUiThread { showOtpChoiceDialog(result) }
                    }
                    else -> throw IllegalStateException("unexpected login result")
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "FAILED: ${e.message}" }
            }
        }
    }

    private fun showOtpChoiceDialog(otp: KiaApi.OtpNeeded) {
        val options = mutableListOf<String>()
        if (otp.hasPhone) options.add("Text message (SMS)")
        if (otp.hasEmail) options.add("Email")
        android.app.AlertDialog.Builder(this)
            .setTitle("New device verification")
            .setMessage("How should Kia send the 6-digit code?")
            .setItems(options.toTypedArray()) { _, which ->
                val notifyType = if (options[which].startsWith("Text")) "SMS" else "EMAIL"
                pendingNotifyType = notifyType
                thread {
                    try {
                        api.sendOtp(otp, notifyType)
                        runOnUiThread {
                            Toast.makeText(this, "Code sent via $notifyType", Toast.LENGTH_SHORT).show()
                            showOtpScreen(notifyType)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- otp screen ----------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showOtpScreen(notifyType: String) {
        clearContent()
        addView(title("Verify code"))
        addView(subtitle("Enter the 6-digit code sent via $notifyType"))

        val otpInput = field("6-digit code", InputType.TYPE_CLASS_NUMBER, imeDone = true)
        addView(otpInput)
        addView(spacer())
        addView(button("Verify") { doVerifyOtp(otpInput) })
        addView(spacer())
        statusText = outputLine()
        addView(statusText)
        otpInput.requestFocus()
    }

    private fun doVerifyOtp(otpInput: EditText) {
        val otp = pendingOtp
        val deviceId = pendingDeviceId
        val notifyType = pendingNotifyType ?: "SMS"
        val code = otpInput.text.toString().trim()
        if (otp == null || deviceId == null) {
            statusText.text = "No verification pending — log in again."
            return
        }
        if (code.length < 6) {
            statusText.text = "Enter the 6-digit code."
            return
        }
        statusText.text = "Verifying…"
        thread {
            try {
                val newToken = api.verifyOtpAndCompleteLogin(
                    getPrefs().getString("username", "").orEmpty(),
                    getPrefs().getString("password", "").orEmpty(),
                    getPrefs().getString("pin", "").orEmpty(),
                    deviceId,
                    otp,
                    code,
                )
                persistSession(newToken)
                pendingOtp = null
                runOnUiThread { showStatusScreen(autoFetch = true) }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "FAILED: ${e.message}" }
            }
        }
    }

    // ---------- status screen -------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showStatusScreen(autoFetch: Boolean = false) {
        clearContent()
        startIndicator()

        val indicator = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        addView(indicator)
        indicatorRunnable = object : Runnable {
            override fun run() {
                val seenAt = PebbleWatchStore.lastWatchMessageAt(this@MainActivity)
                val connected = seenAt > 0 && System.currentTimeMillis() - seenAt < 90_000
                indicator.text = if (connected) "● Pebble connected" else "● Pebble disconnected"
                indicator.setTextColor(if (connected) colorGreen else colorRed)
                uiHandler.postDelayed(this, 5000)
            }
        }
        uiHandler.post(indicatorRunnable!!)

        addView(title("Kia"))
        addView(spacer())
        statusText = outputLine()
        addView(statusText)
        addView(spacer())
        addView(button("Refresh status") { fetchStatus() })
        addView(button("Service log", emphasis = false) { showServiceLog() })
        addView(spacer())
        addView(label("Service log (long-press to select)"))
        addView(outputLineSelectable(getPrefsKey("log_hint")))

        if (autoFetch) {
            fetchStatus()
        } else {
            renderLastKnown()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderLastKnown() {
        val prefs = getPrefs()
        val name = prefs.getString("vehicle_name", null)
        if (name.isNullOrBlank()) {
            statusText.text = "Press Refresh status to load the car."
            return
        }
        statusText.text = buildString {
            append(name)
            append("\n")
            append(if (prefs.getBoolean("last_locked", true)) "Locked" else "Unlocked")
            append("\nFuel level: ${prefs.getInt("last_fuel", -1)}%")
            val odo = prefs.getInt("last_odo", -1)
            if (odo >= 0) append("\nOdometer: $odo mi")
            prefs.getString("last_updated", null)?.let { append("\nLast updated: $it") }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun fetchStatus() {
        statusText.text = "Fetching status…"
        thread {
            try {
                val status = fetchStatusWithAutoRelogin()
                persistLastStatus(status)
                runOnUiThread {
                    statusText.text = buildString {
                        append(status.vehicle.name)
                        append("\n")
                        append(if (status.locked) "Locked" else "Unlocked")
                        append("\nFuel level: ${status.fuelLevelPercent ?: "?"}%")
                        status.odometerMiles?.let { append("\nOdometer: ${it.toInt()} mi") }
                        status.lastUpdatedUtc?.let { append("\nLast updated (UTC): $it") }
                        append("\n\nConnected to Kia ✓")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "FAILED: ${e.message}" }
            }
        }
    }

    private fun fetchStatusWithAutoRelogin(): KiaApi.VehicleStatus {
        val prefs = getPrefs()
        val username = prefs.getString("username", "").orEmpty()
        val password = prefs.getString("password", "").orEmpty()
        val pin = prefs.getString("pin", "").orEmpty()
        val deviceId = prefs.getString("device_id", null)
        val sid = prefs.getString("sid", null)
        val expiry = prefs.getLong("sid_expiry", 0)
        if (username.isBlank() || password.isBlank()) {
            throw KiaApi.ApiError("Not logged in.")
        }
        val vehicleKey = prefs.getString("vehicle_key", null)
        val vehicle = if (!vehicleKey.isNullOrBlank()) {
            KiaApi.Vehicle(
                id = prefs.getString("vehicle_id", null) ?: vehicleKey,
                name = prefs.getString("vehicle_name", null) ?: vehicleKey,
                model = prefs.getString("vehicle_model", null) ?: vehicleKey,
                key = vehicleKey,
            )
        } else {
            null
        }
        if (!sid.isNullOrBlank() && expiry > System.currentTimeMillis() && deviceId != null && vehicle != null) {
            val stored = KiaApi.Token(
                username = username,
                password = password,
                pin = pin,
                deviceId = deviceId,
                sid = sid,
                validUntilMs = expiry,
            )
            try {
                val status = api.getCachedStatus(stored, vehicle)
                if (vehicle == null) persistVehicle(vehicle)
                return status
            } catch (e: Exception) {
                if (e.message?.contains("session invalid", ignoreCase = true) != true) throw e
                ServiceLog.log(this, "sid invalid — re-login")
            }
        }
        val fresh = api.login(username, password, pin, deviceId) as? KiaApi.Token
            ?: throw KiaApi.ApiError("Kia wants device verification — log in again to approve.")
        persistSession(fresh)
        val veh = vehicle ?: resolveVehicle(fresh)
        val status = api.getCachedStatus(fresh, veh)
        persistVehicle(veh)
        return status
    }

    private fun resolveVehicle(token: KiaApi.Token): KiaApi.Vehicle {
        val vehicles = api.getVehicles(token)
        if (vehicles.isEmpty()) throw KiaApi.ApiError("No vehicles on this Kia account.")
        return vehicles.first()
    }

    // ---------- shared helpers --------------------------------------------------

    private fun persistSession(session: KiaApi.Token) {
        getPrefs().edit()
            .putString("device_id", session.deviceId)
            .putString("sid", session.sid)
            .putLong("sid_expiry", session.validUntilMs)
            .putString("username", session.username)
            .putString("password", session.password)
            .putString("pin", session.pin)
            .apply()
    }

    private fun persistVehicle(vehicle: KiaApi.Vehicle) {
        getPrefs().edit()
            .putString("vehicle_key", vehicle.key)
            .putString("vehicle_name", vehicle.name)
            .putString("vehicle_model", vehicle.model)
            .putString("vehicle_id", vehicle.id)
            .apply()
    }

    private fun persistLastStatus(status: KiaApi.VehicleStatus) {
        getPrefs().edit()
            .putBoolean("last_locked", status.locked)
            .putInt("last_fuel", status.fuelLevelPercent ?: -1)
            .putInt("last_odo", status.odometerMiles?.toInt() ?: -1)
            .putString("last_updated", status.lastUpdatedUtc ?: "")
            .apply()
    }

    private fun showServiceLog() {
        Toast.makeText(this, ServiceLog.read(this), Toast.LENGTH_LONG).show()
        // The full log is shown in the selectable log line below the button.
        findAndSetLogText(ServiceLog.read(this))
    }

    private fun findAndSetLogText(text: String) {
        for (i in 0 until rootLayout.childCount) {
            val child = rootLayout.getChildAt(i)
            if (child is TextView && child != statusText && child.text.isNotEmpty() &&
                child.currentTextColor == colorMuted && child.textSize < 13f) {
                child.text = text
                return
            }
        }
        addView(outputLineSelectable(text))
    }

    private fun getPrefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun getPrefsKey(key: String): String {
        return getPrefs().getString(key, "") ?: "(no log entries yet — press the button above)"
    }

    private fun startIndicator() {
        indicatorRunnable?.let { uiHandler.removeCallbacks(it) }
        indicatorRunnable = null
    }

    private fun addView(view: android.view.View) {
        rootLayout.addView(view)
        (view.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(127, 195, 255))
        }
    }

    private fun subtitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(colorMuted)
            setPadding(0, dp(2), 0, dp(10))
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(colorMuted)
        }
    }

    private fun outputLine(): TextView {
        return TextView(this).apply {
            textSize = 14f
            setTextColor(colorText)
            setTextIsSelectable(true)
        }
    }

    private fun outputLineSelectable(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(colorMuted)
            setTextIsSelectable(true)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun field(hint: String, inputType: Int, password: Boolean = false, imeDone: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            textSize = 15f
            isSingleLine = true
            this.inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                inputType
            }
            if (imeDone) {
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            setTextColor(Color.WHITE)
            setHintTextColor(colorMuted)
            background = rounded()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun button(text: String, emphasis: Boolean = true, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(if (emphasis) colorAccent else colorField)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun rounded(fill: Int = colorField): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), colorStroke)
        }
    }

    private fun spacer(): android.view.View {
        return android.view.View(this).also { it.minimumHeight = dp(14) }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS = "kia_companion_settings"
    }
}
