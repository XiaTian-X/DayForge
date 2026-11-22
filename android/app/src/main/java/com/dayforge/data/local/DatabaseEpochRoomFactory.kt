package com.dayforge.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Creates a NEW controlled Room owner. No existing database/DAO/Provider instance is an
 * input. The production binding always uses the real file and original migration list. */
internal interface DatabaseEpochRoomFactory { fun create(): HabitDatabase }
internal class DefaultDatabaseEpochRoomFactory @Inject constructor(
    @param:ApplicationContext private val context: Context
) : DatabaseEpochRoomFactory {
    override fun create(): HabitDatabase = HabitDatabaseProvider.build(context.applicationContext)
}
