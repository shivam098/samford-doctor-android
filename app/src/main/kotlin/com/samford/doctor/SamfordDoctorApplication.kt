package com.samford.doctor

import android.app.Application
import com.samford.doctor.sync.SyncNotifier
import com.samford.doctor.sync.SyncScheduler
import kotlinx.coroutines.launch

open class SamfordDoctorApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Tests override this to inject a fake hospital server. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    protected open fun scheduleBackgroundWork() {
        SyncScheduler.schedulePeriodic(this)
    }

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        SyncNotifier.createChannel(this)
        container.appScope.launch {
            container.restore()
            // Finish any completion a crash or reboot interrupted, then upload what is waiting.
            container.freezer.resumeInterrupted()
            container.server.refresh()
            container.kickSync()
        }
        scheduleBackgroundWork()
    }
}
