package com.forja.app.feature.research

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.forja.app.BuildConfig
import com.forja.app.ForjaApp
import com.forja.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Manual, per-upload consent. No phone permission requests or automatic export jobs. */
class ResearchExportActivity : ComponentActivity() {
    private val categories = listOf("sleep", "meals", "activities", "diagnostics")
    private val checks = linkedMapOf<String, CheckBox>()
    private val controls = mutableListOf<View>()
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var synthetic: CheckBox
    private lateinit var consent: CheckBox
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var send: Button
    private lateinit var delete: Button
    private var body: String? = null
    private var busy = false
    private data class Receipt(val endpoint: String, val token: String, val id: String)
    private var lastReceipt: Receipt? = null
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent the OS from retaining a health-data preview or pairing token in screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
            setBackgroundColor(Color.rgb(19, 25, 33))
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8)); layout.addView(this)
        }
        fun field(hintText: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
            hint = hintText; setText(value); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true); isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            layout.addView(this); controls.add(this)
        }
        fun check(value: String, selected: Boolean = false) = CheckBox(this).apply {
            text = value; setTextColor(Color.WHITE); isChecked = selected; isSaveEnabled = false
            layout.addView(this); controls.add(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; setOnClickListener { action() }; layout.addView(this)
        }
        label("FORJA Research", 28f)
        label("Choose data, review every field, then send one export to your test server. Uploads are retained for 24 hours; you can delete the latest upload here.")
        label("Test server")
        endpoint = field("Server URL", "http://10.0.2.2:8787")
        label("Pairing token")
        token = field("Token from your research server", secret = true)
        synthetic = check("Use synthetic example data", true)
        label("Select categories")
        checks["sleep"] = check("Sleep: times, score, stages, movement and event counts")
        checks["meals"] = check("Meals: times, meal type, calories and nutrient totals")
        checks["activities"] = check("Activities: times, type, distance, duration and calories")
        checks["diagnostics"] = check("Diagnostics: Android API level and five permission states")
        label("Local mode reads this research copy only: the latest 50 records per category from the last seven days. Diagnostics describes the phone only in local mode. Audio, transcripts, photos, location routes and free text are excluded.", 14f)
        controls.add(button("Generate preview") { generate() })
        preview = TextView(this).apply {
            text = "Your exact JSON payload will appear here."
            textSize = 12f; setTextColor(Color.rgb(187, 225, 220)); setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        layout.addView(ScrollView(this).apply { addView(preview) }, LinearLayout.LayoutParams(-1, dp(260)))
        consent = check("I agree to send this exact preview to the displayed test server.")
        controls.remove(consent)
        send = button("Send this preview") { upload() }
        delete = button("Delete latest server upload") { deleteLatest() }
        status = label("Nothing sent.")
        controls.add(button("Open FORJA research app") { startActivity(Intent(this, MainActivity::class.java)) })
        label("The FORJA account and sync features use local Firebase emulators. Other original online features, such as maps and food lookup, can contact their public providers when you open them.", 13f)
        setContentView(ScrollView(this).apply { addView(layout) })
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { invalidatePreview() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        endpoint.addTextChangedListener(watcher); token.addTextChangedListener(watcher)
        synthetic.setOnCheckedChangeListener { _, _ -> invalidatePreview() }
        checks.values.forEach { it.setOnCheckedChangeListener { _, _ -> invalidatePreview() } }
        consent.setOnCheckedChangeListener { _, _ -> refreshControls() }
        refreshControls()
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun refreshControls() {
        controls.forEach { it.isEnabled = !busy }
        consent.isEnabled = !busy && body != null
        send.isEnabled = !busy && body != null && consent.isChecked
        delete.isEnabled = !busy && lastReceipt != null
    }
    private fun invalidatePreview() {
        body = null; consent.isChecked = false
        preview.text = "Settings changed. Generate a new preview."
        refreshControls()
    }
    private fun serverUrl(): String {
        val url = endpoint.text.toString().trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null &&
            url.fragment == null && url.encodedPath == "/") { "Use a server origin without a path, credentials or query." }
        require(url.isHttps || (url.scheme == "http" && url.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))) {
            "Use HTTPS, or HTTP on the local emulator connection."
        }
        return url.toString().removeSuffix("/")
    }
    private fun pairingToken(): String = token.text.toString().trim().also {
        require(it.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "Enter the pairing token from your test server." }
    }

    private fun generate() {
        val selected = categories.associateWith { checks.getValue(it).isChecked }
        if (selected.values.none { it }) { status.text = "Select at least one category."; return }
        try { serverUrl() } catch (e: Exception) { status.text = e.message; return }
        val useSynthetic = synthetic.isChecked
        body = null; consent.isChecked = false; busy = true; refreshControls()
        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { makePayload(selected, useSynthetic).toString(2) }
                require(payload.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Preview exceeds the 128 KiB limit." }
                body = payload; preview.text = payload
                status.text = "Preview ready: ${payload.toByteArray(Charsets.UTF_8).size} bytes. Destination: ${serverUrl()}. Nothing sent."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Preview failed: ${e.message}" }
            finally { busy = false; refreshControls() }
        }
    }

    private fun upload() {
        if (!consent.isChecked || busy) return
        val payload = body ?: return
        val origin: String; val credential: String
        try { origin = serverUrl(); credential = pairingToken() }
        catch (e: Exception) { status.text = e.message; return }
        busy = true; refreshControls()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    val request = Request.Builder().url("$origin/v1/research-export")
                        .header("Authorization", "Bearer $credential")
                        .post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        require(response.code == 201) { "Server returned HTTP ${response.code}." }
                        val receipt = JSONObject(response.body?.string() ?: error("Empty receipt"))
                        val expectedHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        require(receipt.getString("sha256") == expectedHash && receipt.getInt("bytes") == bytes.size &&
                            receipt.getString("run_id") == JSONObject(payload).getString("run_id")) { "Receipt did not match the preview." }
                        UUID.fromString(receipt.getString("receipt_id"))
                        receipt
                    }
                }
                lastReceipt = Receipt(origin, credential, result.getString("receipt_id"))
                body = null; consent.isChecked = false
                status.text = "Upload verified.\nReceipt: ${result.getString("receipt_id")}\nSHA-256: ${result.getString("sha256")}\nCounts: ${result.getJSONObject("counts")}\nExpires: ${java.time.Instant.ofEpochMilli(result.getLong("expires_at"))}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // No automatic retry: the server may have received a request even if its response was lost.
                body = null; consent.isChecked = false
                status.text = "Upload was not confirmed: ${e.message}\nNo automatic retry. If the response was lost, the server may hold this run ID until expiry."
            } finally { busy = false; refreshControls() }
        }
    }

    private fun deleteLatest() {
        val receipt = lastReceipt ?: return
        busy = true; refreshControls()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("${receipt.endpoint}/v1/research-export/${receipt.id}")
                        .header("Authorization", "Bearer ${receipt.token}").delete().build()
                    client.newCall(request).execute().use {
                        require(it.code == 200 || it.code == 404) { "Delete returned HTTP ${it.code}." }
                    }
                }
                lastReceipt = null; status.text = "The latest upload was deleted or has already expired."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status.text = "Deletion not confirmed: ${e.message}" }
            finally { busy = false; refreshControls() }
        }
    }

    private fun json(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private suspend fun makePayload(selected: Map<String, Boolean>, fake: Boolean): JSONObject {
        val data = JSONObject()
        val db = ForjaApp.from(this).db
        val today = LocalDate.now()
        val since = today.minusDays(6).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (selected.getValue("sleep")) {
            val rows = JSONArray()
            if (fake) rows.put(json("start_at" to 1700000000000L, "end_at" to 1700028800000L,
                "score" to 84, "movements" to 7, "deep_min" to 95, "light_min" to 285, "rem_min" to 100, "event_count" to 4))
            else for (s in db.sleepDao().finishedSince(since).first().takeLast(50)) {
                rows.put(json("start_at" to s.startAt, "end_at" to (s.endAt ?: continue), "score" to s.score,
                    "movements" to s.movements, "deep_min" to s.deepMin, "light_min" to s.lightMin,
                    "rem_min" to s.remMin, "event_count" to db.sleepDao().eventsForSessionOnce(s.id).size))
            }
            data.put("sleep", rows)
        }
        if (selected.getValue("meals")) {
            val rows = JSONArray()
            if (fake) rows.put(json("recorded_at" to 1700030000000L, "meal_type" to 0, "kcal" to 450,
                "protein_g" to 25, "carbs_g" to 55, "fat_g" to 14, "grams" to 350))
            else {
                val meals = (0L..6L).flatMap { db.mealDao().mealsForDay(today.minusDays(it).toEpochDay()).first() }
                    .sortedBy { it.at }.takeLast(50)
                for (m in meals) rows.put(json("recorded_at" to m.at, "meal_type" to m.mealType,
                    "kcal" to m.kcal, "protein_g" to m.protein, "carbs_g" to m.carbs, "fat_g" to m.fat, "grams" to m.grams))
            }
            data.put("meals", rows)
        }
        if (selected.getValue("activities")) {
            val rows = JSONArray()
            if (fake) rows.put(json("start_at" to 1700031000000L, "end_at" to 1700032800000L,
                "type" to "walk", "distance_m" to 2200.5, "duration_s" to 1800, "kcal" to 140))
            else for (a in db.activityDao().since(since).first().takeLast(50)) rows.put(json(
                "start_at" to a.startAt, "end_at" to a.endAt, "type" to a.type,
                "distance_m" to a.distanceM, "duration_s" to a.durationS, "kcal" to a.kcal))
            data.put("activities", rows)
        }
        if (selected.getValue("diagnostics")) {
            val permissions = linkedMapOf("camera" to Manifest.permission.CAMERA, "microphone" to Manifest.permission.RECORD_AUDIO,
                "fine_location" to Manifest.permission.ACCESS_FINE_LOCATION,
                "background_location" to "android.permission.ACCESS_BACKGROUND_LOCATION", "images" to "android.permission.READ_MEDIA_IMAGES")
            data.put("diagnostics", json("sdk_int" to if (fake) 35 else Build.VERSION.SDK_INT,
                "permissions" to JSONObject().apply { permissions.forEach { (name, permission) ->
                    put(name, !fake && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
                } }))
        }
        return json("schema_version" to 1, "run_id" to UUID.randomUUID().toString(),
            "source" to json("app" to packageName, "version" to BuildConfig.VERSION_NAME, "data_mode" to if (fake) "synthetic" else "local"),
            "consent" to JSONObject().apply { selected.forEach { (key, value) -> put(key, value) } }, "data" to data)
    }
}
