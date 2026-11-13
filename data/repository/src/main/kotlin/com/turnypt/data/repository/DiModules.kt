package com.turnypt.data.repository

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.crypto.AeadCipher
import com.turnypt.core.crypto.AeadCiphers
import com.turnypt.core.crypto.AesGcmAeadCipher
import com.turnypt.core.crypto.Argon2KeyDerivator
import com.turnypt.core.crypto.AutomaticCipherSelector
import com.turnypt.core.crypto.CipherAlgorithm
import com.turnypt.core.crypto.CkfChunkedCipher
import com.turnypt.core.crypto.CryptoProvider
import com.turnypt.core.crypto.PassphraseCryptoProvider
import com.turnypt.core.crypto.ProtocolRandom
import com.turnypt.core.crypto.RandomSource
import com.turnypt.core.sessions.KeySessionManager
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.TurnyptDatabase
import com.turnypt.data.database.ClipboardHistoryDao
import com.turnypt.data.database.ContactDao
import com.turnypt.data.database.SessionKeyDao
import com.turnypt.data.preferences.CipherPreference
import com.turnypt.data.preferences.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TurnyptDatabase =
        Room.databaseBuilder(context, TurnyptDatabase::class.java, TurnyptDatabase.NAME)
            // v0.5.2: in-place schema upgrades (v2 adds session_keys.imported
            // and the clipboard_history table) — without the explicit
            // migration Room would wipe the vault on update.
            .addMigrations(TurnyptDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideContactDao(database: TurnyptDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideSessionKeyDao(database: TurnyptDatabase): SessionKeyDao = database.sessionKeyDao()

    @Provides
    fun provideClipboardHistoryDao(database: TurnyptDatabase): ClipboardHistoryDao =
        database.clipboardHistoryDao()
}

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideAeadCiphers(): AeadCiphers = AeadCiphers()

    /** Kept for any consumer that wants THE default AEAD (AES-256-GCM). */
    @Provides
    @Singleton
    fun provideAeadCipher(): AeadCipher = AesGcmAeadCipher()

    @Provides
    @Singleton
    fun provideProtocolRandom(): ProtocolRandom = ProtocolRandom(RandomSource.SECURE)

    @Provides
    @Singleton
    fun provideCryptoProvider(
        aeads: AeadCiphers,
        random: ProtocolRandom,
    ): CryptoProvider = PassphraseCryptoProvider(aeads, random)

    @Provides
    @Singleton
    fun provideArgon2KeyDerivator(): Argon2KeyDerivator = Argon2KeyDerivator()

    /** v0.6.0: streaming CKF1 container cipher (images / large files). */
    @Provides
    @Singleton
    fun provideCkfChunkedCipher(aeads: AeadCiphers): CkfChunkedCipher =
        CkfChunkedCipher(aeads)

    /** v0.6.0: resolves the "Automatic" cipher preference by measurement. */
    @Provides
    @Singleton
    fun provideAutomaticCipherSelector(aeads: AeadCiphers): AutomaticCipherSelector =
        AutomaticCipherSelector(aeads)

    /** v0.6.0: the cipher for NEW encryptions, per the user's preference. */
    @Provides
    @Singleton
    fun provideCipherAlgorithmSource(
        settings: SettingsRepository,
        automatic: AutomaticCipherSelector,
    ): CipherAlgorithmSource = CipherAlgorithmSource {
        when (settings.settings.first().preferredCipher) {
            CipherPreference.AES_256_GCM -> CipherAlgorithm.AES_256_GCM
            CipherPreference.CHACHA20_POLY1305 -> CipherAlgorithm.CHACHA20_POLY1305
            CipherPreference.AUTO -> automatic.select()
        }
    }

    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = TimeSource.SYSTEM

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DispatcherProvider()
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(SettingsRepository.defaultDataStore(context))

    @Provides
    @Singleton
    fun provideContactRepository(
        contactDao: ContactDao,
        timeSource: TimeSource,
        dispatchers: DispatcherProvider,
    ): ContactRepository = ContactRepository(contactDao, timeSource, dispatchers)

    @Provides
    @Singleton
    fun provideSessionKeyRepository(
        sessionKeyDao: SessionKeyDao,
        random: ProtocolRandom,
        timeSource: TimeSource,
        dispatchers: DispatcherProvider,
    ): SessionKeyRepository = SessionKeyRepository(sessionKeyDao, random, timeSource, dispatchers)

    /** v0.5.2: the clipboard history (capture policy + pruning live there). */
    @Provides
    @Singleton
    fun provideClipboardHistoryRepository(
        clipboardHistoryDao: ClipboardHistoryDao,
        timeSource: TimeSource,
        dispatchers: DispatcherProvider,
    ): ClipboardHistoryRepository =
        ClipboardHistoryRepository(clipboardHistoryDao, timeSource, dispatchers)

    @Provides
    @Singleton
    fun provideKeySessionManager(
        derivator: Argon2KeyDerivator,
        timeSource: TimeSource,
    ): KeySessionManager = KeySessionManager(derivator, timeSource)

    @Provides
    @Singleton
    fun provideMessageCipher(
        contacts: ContactRepository,
        sessionKeys: SessionKeyRepository,
        sessions: KeySessionManager,
        protector: com.turnypt.core.keys.KeyProtector,
        provider: CryptoProvider,
        settings: SettingsRepository,
        algorithmSource: CipherAlgorithmSource,
        dispatchers: DispatcherProvider,
    ): MessageCipher = MessageCipher(
        contacts = contacts,
        sessionKeys = sessionKeys,
        sessions = sessions,
        protector = protector,
        provider = provider,
        sessionTimeout = {
            settings.settings.first().sessionTimeoutMillis
        },
        dispatchers = dispatchers,
        algorithmSource = algorithmSource,
    )

    /** v0.6.0: the file/image facade — same eras, same sessions, same DI graph. */
    @Provides
    @Singleton
    fun provideMediaCipher(
        contacts: ContactRepository,
        sessionKeys: SessionKeyRepository,
        sessions: KeySessionManager,
        chunked: CkfChunkedCipher,
        random: ProtocolRandom,
        algorithmSource: CipherAlgorithmSource,
        dispatchers: DispatcherProvider,
    ): MediaCipher = MediaCipher(
        contacts = contacts,
        sessionKeys = sessionKeys,
        sessions = sessions,
        chunked = chunked,
        random = random,
        algorithmSource = algorithmSource,
        dispatchers = dispatchers,
    )
}
