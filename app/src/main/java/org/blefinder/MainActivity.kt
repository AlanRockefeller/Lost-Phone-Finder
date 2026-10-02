package org.blefinder

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.blefinder.data.SessionExporter
import org.blefinder.service.Readiness
import org.blefinder.service.SearchService
import org.blefinder.ui.SearchApp

class MainActivity : ComponentActivity() {
    private val repo get() = (application as SearchApplication).repository
    private var exportSession: String? = null
    private var exportAddress: String? = null
    private var exportJson = true
    private var status by mutableStateOf<String?>(null)
    private var requestedSimulation = false
    private var pendingStart = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingStart = false
        if (Readiness.scanPermissions(this)) startSearch(requestedSimulation)
        else status = "Search needs Nearby devices and precise location for BLE proximity. You can grant them in Android app settings. GPS is optional."
    }
    private val document = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val id = exportSession
        val address = exportAddress
        val json = exportJson
        if (result.resultCode == RESULT_OK && uri != null && id != null) {
            lifecycleScope.launch {
                status = "Exporting…"
                try {
                    val snapshot = repo.exportSnapshot(id, address)
                    withContext(Dispatchers.IO) {
                        requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Document provider did not open the file" }.bufferedWriter(Charsets.UTF_8).use {
                            SessionExporter(repo.db).write(snapshot, json, it)
                        }
                    }
                    status = "Export saved. BLE observations may describe other people's devices; share with care."
                } catch (e: Exception) { status = "Export failed: ${e.message}. The destination may contain an incomplete file; retry the export." }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        exportSession = savedInstanceState?.getString("exportSession")
        exportAddress = savedInstanceState?.getString("exportAddress")
        exportJson = savedInstanceState?.getBoolean("exportJson", true) ?: true
        requestedSimulation = savedInstanceState?.getBoolean("simulation", false) ?: false
        enableEdgeToEdge()
        setContent { SearchApp(repo, status, { status = null }, ::requestStart, ::stopSearch, ::export,
            { awake -> if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportSession", exportSession); outState.putString("exportAddress", exportAddress)
        outState.putBoolean("exportJson", exportJson); outState.putBoolean("simulation", requestedSimulation)
        super.onSaveInstanceState(outState)
    }
    private fun requestStart(simulation: Boolean) {
        if (pendingStart) return
        requestedSimulation = simulation
        val missing = Readiness.requiredPermissions().filterNot { Readiness.granted(this, it) }.toMutableList()
        if (Build.VERSION.SDK_INT >= 33 && !Readiness.granted(this, Manifest.permission.POST_NOTIFICATIONS)) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) { pendingStart = true; permissions.launch(missing.toTypedArray()) }
        else startSearch(simulation)
    }
    private fun startSearch(simulation: Boolean) {
        try { startForegroundService(Intent(this, SearchService::class.java).putExtra("simulate", simulation)) }
        catch (e: Exception) { status = "Could not start search: ${e.message}" }
    }
    private fun stopSearch() { startService(Intent(this, SearchService::class.java).setAction(SearchService.STOP)) }
    private fun export(id: String, address: String?, json: Boolean) {
        exportSession = id; exportAddress = address; exportJson = json
        val suffix = if (address == null) "session" else address.replace(":", "")
        document.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (json) "application/json" else "text/csv")
            .putExtra(Intent.EXTRA_TITLE, "ble-search-${id.take(8)}-$suffix.${if (json) "json" else "csv"}"))
    }
}
