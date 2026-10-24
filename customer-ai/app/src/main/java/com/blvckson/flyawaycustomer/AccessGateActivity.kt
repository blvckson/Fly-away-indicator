package com.blvckson.flyawaycustomer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** Fails closed: protected monitoring opens only after remote authorization is confirmed. */
class AccessGateActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var nameInput: EditText
    private val prefs by lazy { getSharedPreferences("customer_access", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
            setBackgroundColor(Color.rgb(41, 43, 47))
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.flyaway_logo)
            contentDescription = "Fly Away Indicator plane logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (112 * resources.displayMetrics.density).toInt()
        ))
        root.addView(AnimatedFlightView(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (92 * resources.displayMetrics.density).toInt()
        ))
        root.addView(TextView(this).apply {
            text = "FLY AWAY INDICATOR"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "CUSTOMER ACCESS"
            textSize = 12f
            setTextColor(Color.rgb(255, 48, 56))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Admin authorization is required before recording or analysis can be used."
            textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, 12, 0, 12)
        })
        nameInput = EditText(this).apply { hint = "Your name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        root.addView(nameInput)
        root.addView(Button(this).apply { text = "REQUEST AUTHORIZATION"; setOnClickListener { requestAuthorization() } })
        root.addView(Button(this).apply { text = "CHECK APPROVAL STATUS"; setOnClickListener { checkExistingAuthorization() } })
        root.addView(Button(this).apply { text = "Optional passcode settings"; setOnClickListener { configurePasscode() } })
        status = TextView(this).apply { text = "Not authorized."; setTextColor(Color.rgb(255, 82, 91)); setPadding(0, 18, 0, 0) }
        root.addView(status)
        styleGate(root)
        setContentView(root)
        if (!prefs.getBoolean("passcode_recommendation_shown", false)) {
            AlertDialog.Builder(this).setTitle("Security recommendation")
                .setMessage("Set an optional passcode to protect Customer AI. You can skip and continue without one.")
                .setPositiveButton("Set passcode") { _, _ -> configurePasscode() }
                .setNegativeButton("Skip for now") { _, _ -> markRecommendationShown() }.show()
        }
        checkExistingAuthorization()
    }

    private fun styleGate(view: View) {
        when (view) {
            is Button -> {
                view.background = GradientDrawable().apply { setColor(Color.rgb(190, 35, 43)); cornerRadius = (12 * resources.displayMetrics.density) }
                view.setTextColor(Color.WHITE); view.textSize = 14f; view.isAllCaps = false
                view.typeface = android.graphics.Typeface.DEFAULT_BOLD
                view.setPadding(14, 10, 14, 10)
                val lp = view.layoutParams
                if (lp is LinearLayout.LayoutParams) { lp.setMargins(0, 4, 0, 4); view.layoutParams = lp }
            }
            is EditText -> {
                view.setTextColor(Color.WHITE); view.setHintTextColor(Color.LTGRAY)
                view.background = GradientDrawable().apply { setColor(Color.rgb(32, 33, 36)); cornerRadius = (10 * resources.displayMetrics.density); setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.rgb(100, 103, 110)) }
                view.setPadding(12, 10, 12, 10)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleGate(view.getChildAt(i))
    }

    private fun markRecommendationShown() { prefs.edit().putBoolean("passcode_recommendation_shown", true).apply() }

    private fun configurePasscode() {
        val input = EditText(this).apply {
            hint = "Passcode (letters, digits, or mixed)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Optional passcode").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString()
                if (value.isBlank()) prefs.edit().remove("passcode_hash").remove("passcode_salt").apply()
                else {
                    val salt = UUID.randomUUID().toString()
                    prefs.edit().putString("passcode_salt", salt).putString("passcode_hash", digest(salt + value)).apply()
                }
                markRecommendationShown()
            }.setNegativeButton("Skip") { _, _ -> markRecommendationShown() }.show()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun endpoint(path: String): String {
        val base = BuildConfig.AUTH_API_BASE_URL.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Authorization server is not configured; Customer AI remains locked." }
        return base + path
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(endpoint(path)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return JSONObject(stream.bufferedReader().use { it.readText() }).put("_http", code)
    }

    private fun checkExistingAuthorization() {
        val installation = prefs.getString("installation_id", null) ?: return
        io.execute {
            val result = runCatching { post("/v1/customer/status", JSONObject().put("installationId", installation)) }
            runOnUiThread {
                result.onSuccess {
                    if (it.optInt("_http") in 200..299 && it.optBoolean("authorized", false)) unlockOrAskPasscode()
                    else status.text = it.optString("message", when (it.optString("status")) { "pending" -> "Request pending Admin approval."; "rejected" -> "Request rejected. Submit a new request if needed."; else -> "Not authorized. Enter your name and request approval." })
                }.onFailure { status.text = it.message ?: "Authorization service unavailable. Access remains locked." }
            }
        }
    }

    private fun requestAuthorization() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) { status.text = "Enter your name first."; return }
        val installation = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString()
        prefs.edit().putString("installation_id", installation).putString("customer_name", name).apply()
        status.text = "Sending authorization request to Admin AI…"
        io.execute {
            val result = runCatching {
                post("/v1/customer/request-authorization", JSONObject().put("name", name).put("installationId", installation))
            }
            runOnUiThread {
                result.onSuccess { json ->
                    if (json.optInt("_http") in 200..299 && json.optBoolean("authorized", false)) unlockOrAskPasscode()
                    else status.text = json.optString("message", "Request sent. Wait for Admin approval.")
                }.onFailure { status.text = it.message ?: "Authorization service unavailable. Access remains locked." }
            }
        }
    }

    private fun unlockOrAskPasscode() {
        val expected = prefs.getString("passcode_hash", null)
        if (expected.isNullOrBlank()) { openMonitor(); return }
        val input = EditText(this).apply {
            hint = "Enter your passcode"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("Unlock Customer AI").setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val salt = prefs.getString("passcode_salt", "") ?: ""
                if (digest(salt + input.text.toString()) == expected) openMonitor()
                else status.text = "Incorrect passcode. Close and reopen the app to try again."
            }.setCancelable(false).show()
    }

    private fun openMonitor() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}