package org.blefinder.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "sessions")
data class SessionEntity(@PrimaryKey val id: String, val startedAt: Long,
    val endedAt: Long? = null, val status: String = "active", val simulated: Boolean = false,
    val settingsJson: String)
@Entity(tableName = "devices", primaryKeys = ["sessionId", "address"],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class DeviceEntity(val sessionId: String, val address: String, val json: String)
@Entity(tableName = "observations", indices = [Index(value = ["sessionId", "id"]), Index(value = ["sessionId", "address", "id"])],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class ObservationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String, val address: String, val timestamp: Long, val rssi: Int, val json: String)
@Entity(tableName = "locations", indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class LocationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String, val json: String)
@Serializable
@Entity(tableName = "events", indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class EventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionId: String,
    val timestamp: Long, val type: String, val detail: String)

@Dao
interface SearchDao {
    @Upsert fun putSession(session: SessionEntity)
    @Query("UPDATE sessions SET endedAt = :at, status = :status WHERE id = :id") fun finish(id: String, at: Long, status: String)
    @Query("UPDATE sessions SET status = 'interrupted' WHERE status = 'active'") fun recoverInterrupted()
    @Upsert fun putDevice(device: DeviceEntity)
    @Insert fun insertObservation(o: ObservationEntity): Long
    @Insert fun insertLocation(location: LocationEntity)
    @Insert fun event(event: EventEntity)
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") fun session(id: String): SessionEntity?
    @Query("SELECT * FROM devices WHERE sessionId = :sessionId ORDER BY address") fun devices(sessionId: String): List<DeviceEntity>
    @Query("SELECT * FROM observations WHERE sessionId = :sessionId AND (:address IS NULL OR address = :address) AND id > :after AND id <= :through ORDER BY id LIMIT 500")
    fun page(sessionId: String, address: String?, after: Long, through: Long): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE sessionId = :sessionId AND address IN (:addresses) AND id > :after AND id <= :through ORDER BY id LIMIT 500")
    fun addressPage(sessionId: String, addresses: Set<String>, after: Long, through: Long): List<ObservationEntity>
    @Query("SELECT COALESCE(MAX(id), 0) FROM observations WHERE sessionId = :sessionId") fun maxId(sessionId: String): Long
    @Query("SELECT COUNT(*) FROM observations WHERE sessionId = :sessionId") fun count(sessionId: String): Long
    @Query("SELECT * FROM locations WHERE sessionId = :sessionId AND id > :after AND id <= :through ORDER BY id LIMIT 500")
    fun locations(sessionId: String, after: Long, through: Long): List<LocationEntity>
    @Query("SELECT COALESCE(MAX(id), 0) FROM locations WHERE sessionId = :sessionId") fun maxLocationId(sessionId: String): Long
    @Query("SELECT * FROM events WHERE sessionId = :sessionId AND id <= :through ORDER BY id") fun events(sessionId: String, through: Long): List<EventEntity>
    @Query("SELECT COALESCE(MAX(id), 0) FROM events WHERE sessionId = :sessionId") fun maxEventId(sessionId: String): Long
    @Query("SELECT * FROM observations WHERE sessionId = :sessionId AND address = :address ORDER BY id DESC LIMIT 50")
    fun recent(sessionId: String, address: String): List<ObservationEntity>
}

@Database(entities = [SessionEntity::class, DeviceEntity::class, ObservationEntity::class, LocationEntity::class, EventEntity::class], version = 1, exportSchema = true)
abstract class SearchDatabase : RoomDatabase() { abstract fun dao(): SearchDao }
