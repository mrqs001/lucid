package lucid.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import lucid.core.model.DataOrigin

@Database(
    entities = [
        SessionEntity::class, SampleEntity::class, EpochEntity::class, CueEventEntity::class,
        SimTruthEntity::class, SessionEventEntity::class, JournalEntity::class, TrainingEntity::class,
        ExperimentEntity::class, BatteryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LucidDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun samples(): SampleDao
    abstract fun epochs(): EpochDao
    abstract fun cues(): CueDao
    abstract fun truth(): TruthDao
    abstract fun events(): EventDao
    abstract fun journal(): JournalDao
    abstract fun training(): TrainingDao
    abstract fun experiments(): ExperimentDao
    abstract fun battery(): BatteryDao

    companion object {
        fun open(context: Context): LucidDatabase =
            Room.databaseBuilder(context, LucidDatabase::class.java, "lucid.db").addMigrations(MIGRATION_1_2).build()

        /** v2: battery timeline. SQL copied from the exported schema (app/schemas/.../2.json). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(BATTERY_TABLE_SQL)
                db.execSQL(BATTERY_INDEX_SQL)
            }
        }
    }
}

class Converters {
    @TypeConverter fun origin(v: DataOrigin): String = v.name
    @TypeConverter fun origin(v: String): DataOrigin = DataOrigin.valueOf(v)
    @TypeConverter fun status(v: SessionStatus): String = v.name
    @TypeConverter fun status(v: String): SessionStatus = SessionStatus.valueOf(v)
    @TypeConverter fun recall(v: Recall): String = v.name
    @TypeConverter fun recall(v: String): Recall = Recall.valueOf(v)
    @TypeConverter fun cueRecognition(v: CueRecognition): String = v.name
    @TypeConverter fun cueRecognition(v: String): CueRecognition = CueRecognition.valueOf(v)
    @TypeConverter fun training(v: TrainingType): String = v.name
    @TypeConverter fun training(v: String): TrainingType = TrainingType.valueOf(v)
}

private const val BATTERY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `battery` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, " +
        "`t` INTEGER NOT NULL, `device` TEXT NOT NULL, `pct` INTEGER NOT NULL, `charging` INTEGER)"
private const val BATTERY_INDEX_SQL = "CREATE INDEX IF NOT EXISTS `index_battery_sessionId_t` ON `battery` (`sessionId`, `t`)"
