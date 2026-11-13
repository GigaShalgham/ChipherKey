package com.turnypt.app.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the fingerprint-gated key store behind its interface so ViewModels
 * stay JVM-testable with a fake (v0.3.0).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BiometricModule {

    @Binds
    @Singleton
    abstract fun bindBiometricKeyStore(impl: AndroidBiometricKeyStore): BiometricKeyStore
}
