package lucid.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lucid.app.data.LucidDatabase
import lucid.app.data.SessionStatus
import lucid.app.data.SettingsRepository
import lucid.app.link.WatchMessenger
import lucid.app.session.Reanalysis
import lucid.app.session.SessionManager
import lucid.app.training.TrainingCoordinator

/** Manual dependency container: small app, no DI framework needed. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = LucidDatabase.open(app)
    val settings = SettingsRepository(app)
    val watch = WatchMessenger(app)
    val sessions = SessionManager(app, db, settings, scope, watch)
    val training = TrainingCoordinator(app, db, settings, scope, watch)

    /** Removes every recorded night and its data. Journal entries and training history stay. */
    suspend fun deleteAllNights() {
        check(!sessions.isRecording) { "stop the running night first" }
        db.samples().deleteAll(); db.epochs().deleteAll(); db.cues().deleteAll(); db.truth().deleteAll()
        db.events().deleteAll(); db.battery().deleteAll(); db.sessions().deleteAll()
    }
}

class LucidApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch {
            // A session still marked RECORDING after a process restart was cut short.
            for (s in container.db.sessions().recording()) {
                container.db.sessions().update(s.copy(status = SessionStatus.ABORTED, endedAt = s.endedAt ?: System.currentTimeMillis()))
            }
            container.training.rescheduleReminders()
            Reanalysis(container.db).run()
        }
    }
}
