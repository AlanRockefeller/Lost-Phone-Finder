package org.blefinder.data

import java.io.File
import android.os.StatFs

/** Safety limits for unusually heavy scan traffic. */
data class StorageLimits(
    val maxDatabaseBytes: Long = 1L * 1024 * 1024 * 1024,
    val minFreeBytes: Long = 256L * 1024 * 1024,
    val maxSessionAddresses: Int = 50_000,
    val maxPendingResults: Int = 8_192,
) {
    init {
        require(maxDatabaseBytes > 0 && minFreeBytes >= 0)
        require(maxSessionAddresses > 0 && maxPendingResults > 0)
    }
}

data class StorageUsage(val databaseBytes: Long, val freeBytes: Long) {
    companion object {
        fun read(db: SearchDatabase): StorageUsage {
            val sqlite = db.openHelper.writableDatabase
            val pages = sqlite.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
            val pageSize = sqlite.query("PRAGMA page_size").use { it.moveToFirst(); it.getLong(0) }
            val logicalBytes = pages * pageSize
            val path = sqlite.path
            if (path.isNullOrEmpty() || path == ":memory:") return StorageUsage(logicalBytes, Long.MAX_VALUE)
            val file = File(path)
            // Include pending WAL writes and shared-memory overhead as well as the main file.
            val bytes = maxOf(logicalBytes, file.length()) + File("$path-wal").length() + File("$path-shm").length()
            // Use space available now; the reserve must not depend on clearing other apps' caches.
            return StorageUsage(bytes, StatFs(requireNotNull(file.parentFile).absolutePath).availableBytes)
        }
    }
    fun limitReason(limits: StorageLimits): String? = when {
        databaseBytes >= limits.maxDatabaseBytes -> "Database storage limit reached (${limits.maxDatabaseBytes / (1024 * 1024)} MiB)."
        freeBytes < limits.minFreeBytes -> "Phone storage is nearly full (less than ${limits.minFreeBytes / (1024 * 1024)} MiB free)."
        else -> null
    }
}
