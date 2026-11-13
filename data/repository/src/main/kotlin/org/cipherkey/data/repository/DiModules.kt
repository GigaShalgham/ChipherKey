package org.cipherkey.data.repository

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import org.cipherkey.core.common.DispatcherProvider
import org.cipherkey.core.crypto.AeadCipher
import org.cipherkey.core.crypto.AeadCiphers
import org.cipherkey.core.crypto.AesGcmAeadCipher
import org.cipherkey.core.crypto.Argon2KeyDerivator
import org.cipherkey.core.crypto.AutomaticCipherSelector
import org.cipherkey.core.crypto.CipherAlgorithm
import org.cipherkey.core.crypto.CkfChunkedCipher
import org.cipherkey.core.crypto.CryptoProvider
import org.cipherkey.core.crypto.PassphraseCryptoProvider
import org.cipherkey.core.crypto.ProtocolRandom
import org.cipherkey.core.crypto.RandomSource
import org.cipherkey.core.sessions.KeySessionManager
import org.cipherkey.core.sessions.TimeSource
import org.cipherkey.data.database.CipherKeyDatabase
import org.cipherkey.data.database.ClipboardHistoryDao
import org.cipherkey.data.database.ContactDao
import org.cipherkey.data.database.SessionKeyDao
import org.cipherkey.data.preferences.CipherPreference
import org.cipherkey.data.preferences.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CipherKeyDatabase =
        Room.databaseBuilder(context, CipherKeyDatabase::class.java, CipherKeyDatabase.NAME)
            // v0.5.2: in-place schema upgrades (v2 adds session_keys.imported
            // and the clipboard_history table) — without the explicit
            // migration Room would wipe the vault on update.
            .addMigrations(CipherKeyDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideContactDao(database: CipherKeyDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideSessionKeyDao(database: CipherKeyDatabase): SessionKeyDao = database.sessionKeyDao()

    @Provides
    fun provideClipboardHistoryDao(database: CipherKeyDatabase): ClipboardHistoryDao =
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
        protector: org.cipherkey.core.keys.KeyProtector,
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
