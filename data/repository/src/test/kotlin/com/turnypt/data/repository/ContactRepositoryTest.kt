package com.turnypt.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.turnypt.core.common.ContactId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRepositoryTest {

    @Test
    fun `create persists and observe lists contacts`() = runTest {
        val device = TestDevice()
        device.contacts.createContact("Bob")
        device.contacts.createContact("Alice")
        val list = device.contacts.observeContacts().first()
        assertEquals(listOf("Alice", "Bob"), list.map { it.displayName })
    }

    @Test
    fun `create trims the name`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("  Bob  ")
        assertEquals("Bob", contact.displayName)
    }

    @Test
    fun `empty name is rejected`() = runTest {
        val device = TestDevice()
        try {
            device.contacts.createContact("   ")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `overly long name is rejected`() = runTest {
        val device = TestDevice()
        try {
            device.contacts.createContact("x".repeat(ContactRepository.MAX_NAME_LENGTH + 1))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `rename updates the stored contact`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Bob")
        val renamed = device.contacts.renameContact(contact.id, "Robert")
        assertNotNull(renamed)
        assertEquals("Robert", renamed!!.displayName)
        assertEquals("Robert", device.contacts.contactName(contact.id))
    }

    @Test
    fun `rename of unknown contact returns null`() = runTest {
        val device = TestDevice()
        assertNull(device.contacts.renameContact(ContactId("nope"), "X"))
    }

    @Test
    fun `delete removes the contact`() = runTest {
        val device = TestDevice()
        val contact = device.contacts.createContact("Bob")
        device.contacts.deleteContact(contact.id)
        assertTrue(device.contacts.observeContacts().first().isEmpty())
        assertNull(device.contacts.contact(contact.id))
    }

    @Test
    fun `contact ids are random and unique`() = runTest {
        val device = TestDevice()
        val a = device.contacts.createContact("Bob")
        val b = device.contacts.createContact("Bob")
        assertTrue(a.id.value != b.id.value)
    }
}
