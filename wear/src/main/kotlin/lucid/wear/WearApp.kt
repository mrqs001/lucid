package lucid.wear

import android.app.Application

class WearApp : Application() {
    lateinit var controller: WatchController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = WatchController(this)
        controller.connect()
    }
}
