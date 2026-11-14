package local.cooldown.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import local.cooldown.data.*

class CooldownApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository: CooldownRepository by lazy {
        RepositoryFactory.create(this, appScope)
    }
}
