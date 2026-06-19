package io.liriliri.aya.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.liriliri.aya.adb.DeviceManager
import io.liriliri.aya.adb.LocalDeviceManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDeviceManager(@ApplicationContext context: Context): DeviceManager {
        return DeviceManager(context)
    }

    @Provides
    @Singleton
    fun provideLocalDeviceManager(@ApplicationContext context: Context): LocalDeviceManager {
        return LocalDeviceManager(context)
    }
}
