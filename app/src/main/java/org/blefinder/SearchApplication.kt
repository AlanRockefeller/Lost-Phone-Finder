package org.blefinder

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import org.blefinder.data.*

class SearchApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, SearchDatabase::class.java, "ble-search.db").build() }
    val repository by lazy { SearchRepository(database, Preferences(this), scope) }
}
