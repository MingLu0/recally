package dev.recally.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.recally.data.settings.DeviceRegistrar
import dev.recally.domain.repository.DeviceRegistrationRepository
import dev.recally.fcm.FcmTokenSource
import dev.recally.fcm.FirebaseFcmTokenSource
import javax.inject.Singleton

/**
 * Push-notification wiring (docs/android.md, "Push notifications"): the FCM
 * side depends on [DeviceRegistrationRepository], never on Retrofit, and the
 * token source is an interface so tests never touch Firebase.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FcmModule {
    @Binds
    @Singleton
    abstract fun bindFcmTokenSource(impl: FirebaseFcmTokenSource): FcmTokenSource

    @Binds
    @Singleton
    abstract fun bindDeviceRegistrationRepository(impl: DeviceRegistrar): DeviceRegistrationRepository
}
