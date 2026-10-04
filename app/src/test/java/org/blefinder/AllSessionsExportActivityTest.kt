package org.blefinder

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.blefinder.core.*
import org.blefinder.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SearchApplication::class)
class AllSessionsExportActivityTest {
    private lateinit var app: SearchApplication
    @Before fun prepare() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        app.repository.allExportSelection() // Wait for initialization before inserting historical sessions.
        withContext(Dispatchers.IO) {
            for (id in listOf("one", "two")) {
                app.database.dao().putSession(SessionEntity(id, 1000, status = "stopped", settingsJson = "{}"))
                val o = observation(address = id)
                app.database.dao().insertObservation(ObservationEntity(sessionId = id, address = id, timestamp = o.timestamp, rssi = o.rssi, json = SearchJson.encodeToString(o)))
            }
        }
    }
    @After fun close() = runBlocking {
        val job = app.scope.coroutineContext[Job]!!
        job.cancel()
        withTimeout(5000) {
            while (!job.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(1) }
        }
        app.database.close()
    }
    @Test fun jsonPickerSurvivesActivityRecreationAndWritesAllSessions() = checkExport(true)
    @Test fun csvPickerSurvivesActivityRecreationAndWritesAllSessions() = checkExport(false)

    private fun checkExport(json: Boolean) = runBlocking {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val bundle = Bundle()
        first.get().javaClass.getDeclaredMethod("exportAll", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(first.get(), json)
        val request = shadowOf(first.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals(if (json) "application/json" else "text/csv", request.intent.type)
        assertEquals("lost-phone-finder-all-sessions.${if (json) "json" else "csv"}", request.intent.getStringExtra(Intent.EXTRA_TITLE))
        first.saveInstanceState(bundle).pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).create(bundle).start().resume()
        val uri = Uri.parse("content://export-provider/all-sessions")
        val output = ByteArrayOutputStream()
        shadowOf(second.get().contentResolver).registerOutputStream(uri, output)
        try {
            assertTrue(second.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri)))
            withTimeout(10000) {
                while (true) {
                    shadowOf(Looper.getMainLooper()).idle()
                    val running = second.get().javaClass.getDeclaredField("exportRunning").apply { isAccessible = true }.getBoolean(second.get())
                    if (!running) break
                    delay(1)
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            if (json) {
                val sessions = Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray
                assertEquals(setOf("one", "two"), sessions.map { it.jsonObject["session"]!!.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
            } else {
                assertTrue(text.startsWith(AllSessionsExporter.CSV_HEADER))
                assertTrue(text.contains("\"observation\",\"one\"")); assertTrue(text.contains("\"observation\",\"two\""))
            }
        } finally { second.pause().stop().destroy() }
    }
}
