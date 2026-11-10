package com.amazon.connect.touchpoint.core

import androidx.sqlite.SQLiteConnection

/**
 * The store's versioned schema. Versioned from the **first** schema, because versioning
 * cannot be retrofitted to stores already on devices: `PRAGMA user_version` records the version, and
 * opening applies every migration above it, in order, in one transaction. A store written by a newer
 * schema than this build knows is not read — [migrate] throws and the store rebuilds, since the server
 * holds the authoritative transcript.
 */
internal class StoreSchema(val version: Int, private val migrations: List<Migration>) {
    /** The statements that take a store from `to - 1` to [to]. */
    class Migration(val to: Int, val statements: List<String>)

    fun migrate(connection: SQLiteConnection) {
        val current = connection.query("PRAGMA user_version") { it.getLong(0) }.first().toInt()
        if (current > version) throw TouchpointException.PersistenceFailure("store schema $current is newer than $version")
        if (current == version) return
        connection.transaction {
            for (m in migrations.filter { it.to > current }.sortedBy { it.to }) m.statements.forEach { connection.execute(it) }
            connection.execute("PRAGMA user_version = $version")
        }
    }

    companion object {
        /** Schema v1. */
        val V1: Migration =
            Migration(
                to = 1,
                statements =
                    listOf(
                        """
                        CREATE TABLE conversation (
                            contact_id TEXT NOT NULL PRIMARY KEY,
                            modality TEXT NOT NULL,
                            last_sequence INTEGER NOT NULL DEFAULT 0,
                            next_local_order INTEGER NOT NULL DEFAULT 0,
                            participants_json TEXT NOT NULL,
                            participant_names_ciphertext BLOB,
                            human_agent_present INTEGER NOT NULL DEFAULT 0,
                            last_activity INTEGER NOT NULL
                        )
                        """.trimIndent(),
                        """
                        CREATE TABLE message (
                            client_message_id TEXT NOT NULL PRIMARY KEY,
                            contact_id TEXT NOT NULL REFERENCES conversation(contact_id) ON DELETE CASCADE,
                            server_message_id TEXT,
                            sequence INTEGER,
                            local_order INTEGER NOT NULL DEFAULT 0,
                            body_ciphertext BLOB NOT NULL,
                            direction TEXT NOT NULL,
                            participant_role TEXT NOT NULL,
                            participant_id TEXT,
                            created_at INTEGER NOT NULL,
                            delivery_state TEXT NOT NULL,
                            interactive_ciphertext BLOB,
                            citations_ciphertext BLOB,
                            is_streaming INTEGER NOT NULL DEFAULT 0,
                            attachment_id TEXT,
                            attachment_type TEXT,
                            attachment_size INTEGER
                        )
                        """.trimIndent(),
                        "CREATE INDEX message_by_sequence ON message(contact_id, sequence, client_message_id)",
                        "CREATE INDEX message_by_server_id ON message(contact_id, server_message_id)",
                        "CREATE INDEX message_pending ON message(contact_id, direction, delivery_state)",
                        "CREATE INDEX message_by_attachment ON message(contact_id, attachment_id)",
                        "CREATE INDEX conversation_by_activity ON conversation(last_activity)",
                    ),
            )

        val CURRENT: StoreSchema = StoreSchema(version = 1, migrations = listOf(V1))
    }
}
