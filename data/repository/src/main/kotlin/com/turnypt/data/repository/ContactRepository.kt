package com.turnypt.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.turnypt.core.common.ContactId
import com.turnypt.core.common.DispatcherProvider
import com.turnypt.core.sessions.TimeSource
import com.turnypt.data.database.ContactDao
import com.turnypt.data.database.ContactEntity
import java.util.UUID

/**
 * Local contact management.
 *
 * Contacts are arbitrary display names with no system address-book coupling
 * (by design: no read-contacts permission, no identifier leakage).
 */
class ContactRepository(
    private val contactDao: ContactDao,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) {

    fun observeContacts(): Flow<List<Contact>> =
        contactDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun contact(contactId: ContactId): Contact? = withContext(dispatchers.io) {
        contactDao.byId(contactId.value)?.toDomain()
    }

    suspend fun contactName(contactId: ContactId): String? = contact(contactId)?.displayName

    suspend fun count(): Int = withContext(dispatchers.io) { contactDao.count() }

    /**
     * Creates a contact. The display name is trimmed and must be 1..64
     * characters. IDs are random UUIDs, unrelated to any external identity.
     */
    suspend fun createContact(displayName: String): Contact = withContext(dispatchers.io) {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "contact name must not be empty" }
        require(name.length <= MAX_NAME_LENGTH) { "contact name must be at most $MAX_NAME_LENGTH chars" }
        val now = timeSource.nowMillis()
        val entity = ContactEntity(
            id = UUID.randomUUID().toString(),
            displayName = name,
            createdAt = now,
            updatedAt = now,
        )
        contactDao.upsert(entity)
        entity.toDomain()
    }

    suspend fun renameContact(contactId: ContactId, newName: String): Contact? =
        withContext(dispatchers.io) {
            val name = newName.trim()
            require(name.isNotEmpty()) { "contact name must not be empty" }
            require(name.length <= MAX_NAME_LENGTH) { "contact name must be at most $MAX_NAME_LENGTH chars" }
            val existing = contactDao.byId(contactId.value)
                ?: return@withContext null
            val updated = existing.copy(displayName = name, updatedAt = timeSource.nowMillis())
            contactDao.upsert(updated)
            updated.toDomain()
        }

    /** Deletes the contact; session eras cascade at the database level. */
    suspend fun deleteContact(contactId: ContactId) = withContext(dispatchers.io) {
        contactDao.delete(contactId.value)
    }

    private fun ContactEntity.toDomain() = Contact(
        id = ContactId(id),
        displayName = displayName,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
