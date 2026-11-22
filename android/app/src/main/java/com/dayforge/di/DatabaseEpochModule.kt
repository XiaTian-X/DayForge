package com.dayforge.di

import com.dayforge.data.local.DatabaseEpochRoomFactory
import com.dayforge.data.local.DefaultDatabaseEpochRoomFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
internal abstract class DatabaseEpochModule {
    @Binds @Singleton abstract fun bindRoomFactory(factory: DefaultDatabaseEpochRoomFactory): DatabaseEpochRoomFactory
}
