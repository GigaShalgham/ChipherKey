package com.turnypt.core.common

import kotlin.jvm.JvmInline

/** Unique local identifier of a contact. Opaque, UUID-based. */
@JvmInline
value class ContactId(val value: String) {
    init {
        require(value.isNotEmpty()) { "ContactId must not be empty" }
    }

    override fun toString(): String = value
}

/** Unique local identifier of a session (key era) belonging to a contact. */
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotEmpty()) { "SessionId must not be empty" }
    }

    override fun toString(): String = value
}
