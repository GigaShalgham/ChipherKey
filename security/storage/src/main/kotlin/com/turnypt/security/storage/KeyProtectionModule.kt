package com.turnypt.security.storage

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.turnypt.core.keys.KeyProtector
import javax.inject.Singleton

/**
 * Binds the production Keystore-backed key protector.
 *
 * The Keystore is process-global; a single instance is shared. Instantiation
 * is lazy (the master key is only touched on first wrap/unwrap).
 */
@Module
@InstallIn(SingletonComponent::class)
object KeyProtectionModule {

    @Provides
    @Singleton
    fun provideKeyProtector(): KeyProtector = AndroidKeystoreKeyProtector()
}
