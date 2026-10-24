package com.blvckson.flyawayadmin

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class AdminActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("admin_setup", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var customerName: EditText
    private lateinit var duration: EditText
    private lateinit var serverKey: EditText
    private lateinit var listing: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("security_recommendation_shown", false)) {
            AlertDialog.Builder(this).setTitle("Secure Admin AI")
                .setMessage("Set an optional app passcode. If you set it, it cannot be changed inside the app; reinstalling clears this local setup. You may skip.")
                .setPositiveButton("Set passcode") { _, _ -> setInitialPasscode() }
                .setNegativeButton("Skip for now") { _, _ -> prefs.edit().putBoolean("security_recommendation_shown", true).apply(); showUnlockOrDashboard() }
                .setOnCancelListener { prefs.edit().putBoolean("security_recommendation_shown", true).apply(); showUnlockOrDashboard() }
                .show()
        } else showUnlockOrDashboard()
    }

    private fun showUnlockOrDashboard() {
        val hash = prefs.getString("admin_passcode_hash", null)
        if (hash.isNullOrBlank()) { showDashboard(); return }
        val input = EditText(this).apply { hint = "Admin passcode"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Unlock Admin AI").setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val salt = prefs.getString("admin_passcode_salt", "") ?: ""
                val entered = digest(salt + input.text.toString())
                if (entered == hash) showDashboard() else {
                    statusMessage("Incorrect passcode. Close and reopen Admin AI to try again.")
                    showUnlockOrDashboard()
                }
            }.setCancelable(false).show()
    }

    private fun setInitialPasscode() {
        if (!prefs.getString("admin_passcode_hash", null).isNullOrBlank()) {
            showUnlockOrDashboard(); return
        }
        val input = EditText(this).apply { hint = "Create permanent local passcode"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("Set Admin AI passcode").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString()
                if (value.isNotBlank()) {
                    val salt = UUID.randomUUID().toString()
                    prefs.edit().putString("admin_passcode_salt", salt).putString("admin_passcode_hash", digest(salt + value)).apply()
                }
                prefs.edit().putBoolean("security_recommendation_shown", true).apply()
                showDashboard()
            }.setNegativeButton("Skip") { _, _ ->
                prefs.edit().putBoolean("security_recommendation_shown", true).apply()
                showDashboard()
            }.show()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun showDashboard() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 18, 18, 18) }
        root.addView(TextView(this).apply { text = "Fly Away Admin AI"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Customer authorizations · default period: ${prefs.getInt(\"default_period_days\", 14)} days"
            setPadding(0, 8, 0, 12)
        })
        serverKey = EditText(this).apply {
            hint = "Admin server key (provided during server setup)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("admin_server_key", ""))
        }
        root.addView(serverKey)
        root.addView(Button(this).apply {
            text = "Set permanent Admin AI passcode (optional)"
            setOnClickListener { setInitialPasscode() }
        })
        root.addView(Button(this).apply {
            text = "Save server key"
            setOnClickListener { prefs.edit().putString("admin_server_key", serverKey.text.toString().trim()).apply(); statusMessage("Server key saved on this device.") }
        })
        customerName = EditText(this).apply { hint = "Customer's full name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        duration = EditText(this).apply { hint = "Authorization period in days (default 14)"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("default_period_days", 14).toString()) }
        root.addView(customerName); root.addView(duration)
        root.addView(Button(this).apply { text = "Generate single-use authorization code"; setOnClickListener { createAuthorization() } })
        root.addView(Button(this).apply { text = "Refresh all customers"; setOnClickListener { loadCustomers() } })
        root.addView(Button(this).apply { text = "Set default authorization period"; setOnClickListener { setDefaultPeriod() } })
        status = TextView(this).apply { text = "Ready. Server must be configured before codes can be issued."; setPadding(0, 12, 0, 12) }
        root.addView(status)
        listing = TextView(this).apply { text = "Customer list has not been loaded."; setTextIsSelectable(true) }
        val scroll = ScrollView(this)
        scroll.addView(listing)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun statusMessage(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun endpoint(path: String): String {
        val base = BuildConfig.AUTH_API_BASE_URL.trim().trimEnd('/')
        require(base.startsWith("https://")) { "Authorization server URL is not configured in this build." }
        return base + path
    }

    private fun request(path: String, data: JSONObject): JSONObject {
        val key = prefs.getString("admin_server_key", "").orEmpty()
        require(key.isNotBlank()) { "Enter the admin server key first." }
        data.put("adminKey", key)
        val conn = URL(endpoint(path)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(data.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return JSONObject(stream.bufferedReader().use { it.readText() }).put("_http", code)
    }

    private fun createAuthorization() {
        val name = customerName.text.toString().trim()
        val days = duration.text.toString().toIntOrNull() ?: 14
        if (name.isBlank() || days < 1) { statusMessage("Enter a customer name and a period of at least one day."); return }
        statusMessage("Generating authorization…")
        io.execute {
            val result = runCatching { request("/v1/admin/authorizations/create", JSONObject().put("name", name).put("durationDays", days)) }
            runOnUiThread {
                result.onSuccess { json ->
                    if (json.optInt("_http") in 200..299) {
                        val code = json.optString("code", "")
                        status.text = "Authorization code for $name (share privately; single use):\n$code\nDuration: $days days from activation."
                        loadCustomers()
                    } else status.text = json.optString("message", "Could not create authorization.")
                }.onFailure { status.text = it.message ?: "Could not contact authorization service." }
            }
        }
    }

    private fun setDefaultPeriod() {
        val input = EditText(this).apply { hint = "Default period in days"; inputType = InputType.TYPE_CLASS_NUMBER; setText("14") }
        AlertDialog.Builder(this).setTitle("Default authorization period").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val days = input.text.toString().toIntOrNull() ?: 14
                if (days < 1) { statusMessage("Period must be at least one day."); return@setPositiveButton }
                io.execute {
                    val result = runCatching { request("/v1/admin/settings/default-period", JSONObject().put("durationDays", days)) }
                    runOnUiThread { result.onSuccess { prefs.edit().putInt("default_period_days", days).apply(); if (::duration.isInitialized) duration.setText(days.toString()); statusMessage(it.optString("message", "Default period updated to $days days.")) }.onFailure { statusMessage(it.message ?: "Update failed.") } }
                }
            }.show()
    }

    private fun loadCustomers() {
        statusMessage("Loading customer authorizations…")
        io.execute {
            val result = runCatching { request("/v1/admin/authorizations/list", JSONObject()) }
            runOnUiThread {
                result.onSuccess { json ->
                    if (json.optInt("_http") !in 200..299) { status.text = json.optString("message", "Could not load customers."); return@onSuccess }
                    val rows = json.optJSONArray("authorizations") ?: JSONArray()
                    val out = StringBuilder("Saved customers: ${rows.length()}\n\n")
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val name = row.optString("name", "Unnamed")
                        val id = row.optString("id")
                        val statusText = row.optString("status", "Pending")
                        val activated = row.optString("activatedAt", "Not activated")
                        val expires = row.optString("expiresAt", "Not yet activated")
                        val remaining = row.optLong("remainingSeconds", 0L)
                        out.append("$name\nStatus: $statusText\nActivated: $activated\nExpires: $expires\n")
                        out.append("Remaining: ${if (remaining > 0) remaining / 86400 else 0} days\nDevice: ${row.optString("installationId", "Not registered")}\nCode state: ${row.optString("codeState", "unknown")}\n")
                        out.append("ID: $id\n\n")
                    }
                    listing.text = out.toString()
                    status.text = "Customer list updated."
                    if (rows.length() > 0) showCustomerActions(rows)
                }.onFailure { status.text = it.message ?: "Could not contact authorization service." }
            }
        }
    }

    private fun showCustomerActions(rows: JSONArray) {
        val choices = ArrayList<String>()
        val ids = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            choices.add("${row.optString("name")} — ${row.optString("status")}")
            ids.add(row.optString("id"))
        }
        AlertDialog.Builder(this).setTitle("Manage customer")
            .setItems(choices.toTypedArray()) { _, index -> chooseAction(ids[index], choices[index]) }.show()
    }

    private fun chooseAction(id: String, label: String) {
        val input = EditText(this).apply { hint = "Days to add or subtract"; inputType = InputType.TYPE_CLASS_NUMBER; setText("14") }
        val options = arrayOf("Extend from current expiry", "Shorten from current expiry", "Deactivate now", "Reactivate / renew")
        AlertDialog.Builder(this).setTitle(label).setItems(options) { _, which ->
            val action = when (which) { 0 -> "extend"; 1 -> "shorten"; 2 -> "deactivate"; else -> "reactivate" }
            if (action == "deactivate" || action == "reactivate") updateCustomer(id, action, 0)
            else AlertDialog.Builder(this).setTitle("Period in days").setView(input)
                .setPositiveButton("Apply") { _, _ -> updateCustomer(id, action, input.text.toString().toIntOrNull() ?: 14) }.show()
        }.show()
    }

    private fun updateCustomer(id: String, action: String, days: Int) {
        if (action != "deactivate" && days < 1) { statusMessage("Enter at least one day."); return }
        io.execute {
            val result = runCatching { request("/v1/admin/authorizations/update", JSONObject().put("id", id).put("action", action).put("durationDays", days)) }
            runOnUiThread {
                result.onSuccess { statusMessage(it.optString("message", "Customer authorization updated.")); loadCustomers() }
                    .onFailure { statusMessage(it.message ?: "Update failed.") }
            }
        }
    }
}
