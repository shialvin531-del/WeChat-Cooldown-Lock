package local.cooldown.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope

object RepositoryFactory {
    fun create(context: Context, scope: CoroutineScope): CooldownRepository = CooldownRepository(
        SettingsStore(context, scope),
        Room.databaseBuilder(context.applicationContext, CooldownDatabase::class.java, "private-cooldown.db").build(),
        AndroidDeviceClock(context.applicationContext), scope,
    )
}
