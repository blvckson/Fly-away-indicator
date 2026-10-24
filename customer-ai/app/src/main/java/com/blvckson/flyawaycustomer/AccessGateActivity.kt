package com.blvckson.flyawaycustomer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 24) }
        root.addView(TextView(this).apply { text = "Fly Away Customer AI"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Admin authorization is required before recording or analysis can be used."
            textSize = 16f; setPadding(0, 16, 0, 16)
        })
        nameInput = EditText(this).apply { hint = "Your name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        root.addView(nameInput)
        root.addView(Button(this).apply { text = "REQUEST AUTHORIZATION"; setOnClickListener { requestAuthorization() } })
        root.addView(Button(this).apply { text = "CHECK APPROVAL STATUS"; setOnClickListener { checkExistingAuthorization() } })
        root.addView(Button(this).apply { text = "Optional passcode settings"; setOnClickListener { configurePasscode() } })
        status = TextView(this).apply { text = "Not authorized."; setPadding(0, 18, 0, 0) }
        root.addView(status)
        setContentView(root)
        if (!prefs.getBoolean("passcode_recommendation_shown", false)) {
            AlertDialog.Builder(this).setTitle("Security recommendation")
                .setMessage("Set an optional passcode to protect Customer AI. You can skip and continue without one.")
                .setPositiveButton("Set passcode") { _, _ -> configurePasscode() }
                .setNegativeButton("Skip for now") { _, _ -> markRecommendationShown() }.show()
        }
        checkExistingAuthorization()
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
