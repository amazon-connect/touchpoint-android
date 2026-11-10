package com.amazon.connect.touchpoint.core

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The durable store. SQL over the `androidx.sqlite` driver interface: the Android module
 * passes the framework driver, and the JVM test tier the bundled one, so every behaviour below is
 * exercised against real SQLite with no device.
 *
 * **Never throws from [open] for a bad store**: a store that cannot be opened or migrated — corrupt, or
 * written by a newer schema — is dropped and rebuilt, because the server is the transcript authority
 * and the SDK must never crash its host.
 *
 * Message bodies are encrypted (layer 2) with AES-GCM under an SDK-custodied key held in the
 * [SecureStore]; each body's ciphertext is bound to its row's client id as associated data, so a body
 * cannot be moved between rows by editing the file. Metadata stays in the clear so the store stays
 * queryable. Layer 1 — a backup-excluded, credential-encrypted location — is the caller's
 * [directory]; the Android module chooses it.
 */
public class ConversationStore private constructor(
    private val directory: File,
    private val secureStore: SecureStore,
    private val driver: SQLiteDriver,
    private val retention: Duration,
    private val now: () -> Instant,
    private val io: CoroutineDispatcher,
    private var key: SecretKey,
    initialOutcome: OpenOutcome,
) {
    public enum class OpenOutcome {
        OPENED,
        REBUILT_AFTER_FAILURE,
        RESET_AFTER_KEY_LOSS,
    }

    /** What [append] did with a message. */
    public sealed interface AppendOutcome {
        /** A new row. */
        public data object Appended : AppendOutcome

        /** Nothing written: a row with this server id — or this client id — already exists. */
        public data object Duplicate : AppendOutcome

        /**
         * Nothing new written: an **outbound** message from the server matched an unacknowledged local
         * echo with the same body — the send whose acknowledgement was lost after the server accepted
         * it. That echo now carries the server id, sequence and `SENT`. Body is the only correlation
         * key until the server-side `clientToken` lands.
         */
        public data class Adopted(val clientMessageId: UUID) : AppendOutcome
    }

    @Volatile public var lastOpenOutcome: OpenOutcome = initialOutcome
        private set

    /**
     * Rows skipped on the most recent transcript read — whole or paged — because their stored body
     * could not be opened (corruption, bit rot). Per read, not cumulative. For diagnostics.
     */
    @Volatile public var undecryptableRowsSkipped: Int = 0
        private set

    private val mutex = Mutex()
    private val databaseFile = File(directory, DATABASE_FILE)
    @Volatile private var connection: SQLiteConnection? = null

    public companion object {
        public const val ENCRYPTION_KEY_SECURE_STORE_KEY: String = "store.fieldkey.v1"
        public val DEFAULT_RETENTION: Duration = Duration.ofDays(30)

        /** The largest page [messages] returns; a larger `limit` is clamped. */
        public const val MAX_PAGE: Int = 1000

        internal const val DATABASE_FILE = "touchpoint.db"
        private const val KEY_FINGERPRINT_FILE = "fieldkey.fingerprint"
        private const val FINGERPRINT_BYTES = 32
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        /**
         * @param directory A directory the SDK owns, namespaced by the caller so a host's own databases
         *   are never touched.
         * @param retention Conversations idle longer than this are removed by [pruneExpired].
         * @param schema Test seam: the schema to open with. Production always uses [StoreSchema.CURRENT].
         */
        public suspend fun open(
            directory: File,
            secureStore: SecureStore,
            driver: SQLiteDriver,
            retention: Duration = DEFAULT_RETENTION,
            now: () -> Instant = { Instant.now() },
            io: CoroutineDispatcher = Dispatchers.IO,
        ): ConversationStore = open(directory, secureStore, driver, retention, now, io, StoreSchema.CURRENT)

        internal suspend fun open(
            directory: File,
            secureStore: SecureStore,
            driver: SQLiteDriver,
            retention: Duration,
            now: () -> Instant,
            io: CoroutineDispatcher,
            schema: StoreSchema,
        ): ConversationStore =
            try {
                openOrRebuild(directory, secureStore, driver, retention, now, io, schema)
            } catch (e: TouchpointException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Disk full, an unwritable fingerprint: nothing but a TouchpointException leaves.
                throw TouchpointException.PersistenceFailure("store open failed: ${e.javaClass.simpleName}")
            }

        private suspend fun openOrRebuild(
            directory: File,
            secureStore: SecureStore,
            driver: SQLiteDriver,
            retention: Duration,
            now: () -> Instant,
            io: CoroutineDispatcher,
            schema: StoreSchema,
        ): ConversationStore {
            withContext(io) {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw TouchpointException.PersistenceFailure("cannot create the store directory")
                }
            }
            // Key custody is the SDK's. Loss means the ciphertext is unreadable, so the store resets.
            val (key, keyOutcome) = loadOrCreateKey(secureStore, directory, io)
            val store = ConversationStore(directory, secureStore, driver, retention, now, io, key, keyOutcome)
            withContext(io) {
                if (keyOutcome == OpenOutcome.RESET_AFTER_KEY_LOSS) wipeFiles(directory)
                // The framework driver repairs a corrupt file itself, by deleting it, and reports nothing.
                // A file that is not SQLite at all is caught here first, on every driver, so the
                // integrator is told.
                if (hasForeignHeader(File(directory, DATABASE_FILE))) {
                    wipeFiles(directory)
                    store.lastOpenOutcome = OpenOutcome.REBUILT_AFTER_FAILURE
                }
                try {
                    store.connection = store.connect(schema)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Corrupt, unreadable, or written by a newer schema: drop it and start again.
                    store.connection?.close()
                    store.connection = null
                    wipeFiles(directory)
                    store.connection =
                        try {
                            store.connect(schema)
                        } catch (e: Exception) {
                            throw TouchpointException.PersistenceFailure("store rebuild failed: ${e.javaClass.simpleName}")
                        }
                    store.lastOpenOutcome = OpenOutcome.REBUILT_AFTER_FAILURE
                }
            }
            return store
        }

        /**
         * Loads the field key, or creates one. A fingerprint file beside the store detects the case
         * where the secure-store item is gone but the ciphertext remains — that is key loss, and the
         * only honest response is a reset.
         */
        private suspend fun loadOrCreateKey(secureStore: SecureStore, dir: File, io: CoroutineDispatcher): Pair<SecretKey, OpenOutcome> {
            val fp = File(dir, KEY_FINGERPRINT_FILE)
            // An empty or short file — a crash mid-write — is unknown, not a mismatch: it must not wipe a good store.
            val storedFingerprint = withContext(io) { ignoringFailure { if (fp.isFile) fp.readBytes().takeIf { it.size == FINGERPRINT_BYTES } else null } }
            val data = secureStore.get(ENCRYPTION_KEY_SECURE_STORE_KEY)
            if (data != null && data.size == KEY_BYTES) {
                val fresh = fingerprint(data)
                if (storedFingerprint == null) withContext(io) { writeFingerprint(dir, fresh) }
                // A fingerprint on disk that does not match the key we were handed means the ciphertext
                // was written under a different key: unreadable, so reset rather than fail on every read.
                val outcome = if (storedFingerprint != null && !storedFingerprint.contentEquals(fresh)) OpenOutcome.RESET_AFTER_KEY_LOSS else OpenOutcome.OPENED
                return SecretKeySpec(data, "AES") to outcome
            }
            val hadStore = storedFingerprint != null
            val key = mintKey(secureStore, dir, io)
            return key to if (hadStore) OpenOutcome.RESET_AFTER_KEY_LOSS else OpenOutcome.OPENED
        }

        private suspend fun mintKey(secureStore: SecureStore, dir: File, io: CoroutineDispatcher): SecretKey {
            val bytes = ByteArray(KEY_BYTES).also(random::nextBytes)
            secureStore.set(ENCRYPTION_KEY_SECURE_STORE_KEY, bytes, SecureStoreAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY)
            withContext(io) { writeFingerprint(dir, fingerprint(bytes)) }
            return SecretKeySpec(bytes, "AES")
        }

        private fun fingerprint(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(key)

        /** Through a temporary file and a rename, so a crash leaves the old fingerprint or the new one, never half of one. */
        private fun writeFingerprint(dir: File, bytes: ByteArray) {
            val tmp = File(dir, "$KEY_FINGERPRINT_FILE.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, KEY_FINGERPRINT_FILE))) {
                tmp.delete()
                throw java.io.IOException("cannot replace the key fingerprint")
            }
        }

        private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

        private fun hasForeignHeader(file: File): Boolean {
            if (!file.isFile || file.length() == 0L) return false
            val head = ByteArray(SQLITE_HEADER.size)
            val read = file.inputStream().use { it.read(head) }
            return read < head.size || !head.contentEquals(SQLITE_HEADER)
        }

        private fun wipeFiles(dir: File) {
            dir.listFiles()?.filter { it.name != KEY_FINGERPRINT_FILE }?.forEach { it.deleteRecursively() }
        }
    }

    // Conversations

    public suspend fun upsertConversation(contactId: String, modality: Modality, lastActivity: Instant = now()) {
        db {
            execute(
                "INSERT INTO conversation(contact_id, modality, participants_json, last_activity) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(contact_id) DO UPDATE SET modality = excluded.modality, last_activity = excluded.last_activity",
                contactId,
                modality.rawValue,
                encodeParticipants(ParticipantSet()),
                lastActivity.toEpochMilli(),
            )
        }
    }

    public suspend fun conversation(contactId: String): ConversationRecord? =
        db {
            query(
                "SELECT contact_id, modality, last_sequence, participants_json, human_agent_present, last_activity, participant_names_ciphertext FROM conversation WHERE contact_id = ?",
                contactId,
            ) { record(it) }.firstOrNull()
        }

    public suspend fun setParticipants(contactId: String, set: ParticipantSet) {
        // An existence check, not `changes()`: the framework driver may answer a read on another of
        // its pooled connections, where `changes()` describes nothing we did.
        db {
            transaction {
                val known = query("SELECT participant_names_ciphertext FROM conversation WHERE contact_id = ?", contactId) { openNames(contactId, it.blobOrNull(0)) }.firstOrNull()
                    ?: throw TouchpointException.ContactNotFound()
                // Names accumulate: someone who leaves is still the sender of what they wrote.
                val names = known + set.participants.filter { it.displayName.isNotBlank() }.associate { it.id to it.displayName }
                execute(
                    "UPDATE conversation SET participants_json = ?, participant_names_ciphertext = ?, human_agent_present = ?, last_activity = ? WHERE contact_id = ?",
                    encodeParticipants(set),
                    seal(json.encodeToString(NAMES, names), namesAad(contactId)),
                    set.humanAgentPresent,
                    now().toEpochMilli(),
                    contactId,
                )
            }
        }
    }

    /** The fast path for resume: reads the denormalised flag without decoding the participant blob. */
    public suspend fun humanAgentPresent(contactId: String): Boolean =
        db { query("SELECT human_agent_present FROM conversation WHERE contact_id = ?", contactId) { it.getLong(0) != 0L }.firstOrNull() ?: false }

    /**
     * Messages are deleted explicitly, never by the schema's cascade: `PRAGMA foreign_keys` is
     * per connection, and the framework driver pools connections, so the cascade cannot be relied on
     * to have run on the one that writes.
     */
    public suspend fun deleteConversation(contactId: String) {
        db {
            transaction {
                execute("DELETE FROM message WHERE contact_id = ?", contactId)
                execute("DELETE FROM conversation WHERE contact_id = ?", contactId)
            }
        }
    }

    /** Full wipe, including the field-encryption key. */
    /**
     * Full wipe, including the field-encryption key. The rows go first; then a new key
     * **replaces** the old one, and the store switches to it only once it is safely in the secure
     * store. A failure to mint (a locked device) leaves the rows gone and the old key still valid for
     * what is written next, and is thrown. Runs to completion once started, even if its caller is
     * cancelled: a half-done wipe is the one outcome worse than either.
     */
    public suspend fun deleteAllConversations() {
        withContext(io + NonCancellable) {
            mutex.withLock {
                val c = connection ?: throw TouchpointException.PersistenceFailure("store is closed")
                try {
                    c.transaction {
                        c.execute("DELETE FROM message")
                        c.execute("DELETE FROM conversation")
                    }
                    key = mintKey(secureStore, directory, io)
                } catch (e: TouchpointException) {
                    throw e
                } catch (e: Exception) {
                    throw TouchpointException.PersistenceFailure("wipe failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Removes conversations idle longer than the retention window. Returns their contact ids so the
     * caller can reap anything keyed on them elsewhere (persisted credentials).
     */
    public suspend fun pruneExpired(now: Instant = this.now()): List<String> =
        db {
            val cutoff = now.minus(retention).toEpochMilli()
            transaction {
                val stale = query("SELECT contact_id FROM conversation WHERE last_activity < ?", cutoff) { it.getText(0) }
                execute("DELETE FROM message WHERE contact_id IN (SELECT contact_id FROM conversation WHERE last_activity < ?)", cutoff)
                execute("DELETE FROM conversation WHERE last_activity < ?", cutoff)
                stale
            }
        }

    /** The row holding the file Connect calls [attachmentId] in [contactId], if any. */
    public suspend fun messageIdForAttachment(contactId: String, attachmentId: String): UUID? =
        db {
            query("SELECT client_message_id FROM message WHERE contact_id = ? AND attachment_id = ?", contactId, attachmentId) { UUID.fromString(it.getText(0)) }.firstOrNull()
        }

    /** Every stored contact id. Used by the full wipe to reap persisted credentials too. */
    public suspend fun allContactIds(): List<String> = db { query("SELECT contact_id FROM conversation") { it.getText(0) } }

    // Messages

    public suspend fun append(contactId: String, message: StoredMessage): AppendOutcome =
        db {
            transaction {
                val conv =
                    query("SELECT next_local_order, last_sequence FROM conversation WHERE contact_id = ?", contactId) {
                        it.getLong(0) to it.getLong(1)
                    }.firstOrNull() ?: throw TouchpointException.ContactNotFound()
                // The server id first, then adoption of a lost-ack echo, then the insert. The
                // client id check comes last because a server copy that carries our own client id (the
                // planned `clientToken`) must adopt that row, not be dropped as a duplicate of it.
                val sid = message.serverMessageId
                // A file is one row whichever copy comes first — the session's after the upload, or the
                // server's echo of it — keyed on Connect's attachment id; the server's id is kept.
                val attachment = message.attachmentId
                if (attachment != null) {
                    val existing =
                        query("SELECT client_message_id, server_message_id FROM message WHERE contact_id = ? AND attachment_id = ?", contactId, attachment) {
                            UUID.fromString(it.getText(0)) to it.textOrNull(1)
                        }.firstOrNull()
                    if (existing != null) {
                        // The size only the session knows, kept whichever copy came first.
                        if (message.attachmentSize != null) {
                            execute("UPDATE message SET attachment_size = COALESCE(attachment_size, ?) WHERE client_message_id = ?", message.attachmentSize, existing.first.toString())
                        }
                        if (existing.second != null || sid == null) return@transaction AppendOutcome.Duplicate
                        execute(
                            "UPDATE message SET server_message_id = ?, sequence = ? WHERE client_message_id = ?",
                            sid,
                            message.sequence,
                            existing.first.toString(),
                        )
                        raiseLastSequence(contactId, message.sequence)
                        return@transaction AppendOutcome.Adopted(existing.first)
                    }
                }
                if (sid != null) {
                    if (exists("SELECT 1 FROM message WHERE contact_id = ? AND server_message_id = ?", contactId, sid)) {
                        return@transaction AppendOutcome.Duplicate
                    }
                    // A file is matched on its attachment id alone: a text whose words are its name is not it.
                    if (message.direction == Direction.OUTBOUND && attachment == null) {
                        // Oldest first: of two identical pending bodies, the earlier send was acknowledged first.
                        val pending = pendingRows(contactId)
                        val echo =
                            pending.firstOrNull { it.clientMessageId == message.clientMessageId }
                                ?: pending.firstOrNull { row -> tryDecrypt(row) == message.body }
                        if (echo != null) {
                            execute(
                                "UPDATE message SET server_message_id = ?, sequence = ?, delivery_state = ? WHERE client_message_id = ?",
                                sid,
                                message.sequence,
                                DeliveryState.SENT.rawValue,
                                echo.clientMessageId.toString(),
                            )
                            raiseLastSequence(contactId, message.sequence)
                            return@transaction AppendOutcome.Adopted(echo.clientMessageId)
                        }
                    }
                }
                // Client ids are the primary key, so unique across conversations, not per contact.
                if (exists("SELECT 1 FROM message WHERE client_message_id = ?", message.clientMessageId.toString())) {
                    return@transaction AppendOutcome.Duplicate
                }
                execute(
                    "INSERT INTO message(client_message_id, contact_id, server_message_id, sequence, local_order, body_ciphertext, direction, " +
                        "participant_role, participant_id, created_at, delivery_state, interactive_ciphertext, citations_ciphertext, is_streaming, " +
                        "attachment_id, attachment_type, attachment_size) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    message.clientMessageId.toString(),
                    contactId,
                    sid,
                    message.sequence,
                    conv.first,
                    encrypt(message.body, message.clientMessageId),
                    message.direction.rawValue,
                    message.participantRole.rawValue,
                    message.participantId,
                    message.createdAt.toEpochMilli(),
                    message.deliveryState.rawValue,
                    // Content, as the body is: an interactive message repeats its title and choices.
                    message.interactiveJson?.let { seal(it, fieldAad(message.clientMessageId, "interactive")) },
                    message.citationsJson?.let { seal(it, fieldAad(message.clientMessageId, "citations")) },
                    message.isStreaming,
                    attachment,
                    message.attachmentContentType,
                    message.attachmentSize,
                )
                // Activity advances to the message's own time, never backwards: a replayed old message
                // must not resurrect a conversation that retention is about to prune.
                execute(
                    "UPDATE conversation SET next_local_order = next_local_order + 1, last_activity = MAX(last_activity, ?) WHERE contact_id = ?",
                    message.createdAt.toEpochMilli(),
                    contactId,
                )
                raiseLastSequence(contactId, message.sequence)
                AppendOutcome.Appended
            }
        }

    /**
     * Applies a receipt from the other side to a message in [contactId]. Returns the message's client id
     * when a row changed, `null` when there is nothing to do: unknown server id, or a receipt that
     * would move the state backwards (retransmitted or out of order). Receipts are advisory, so neither
     * case throws.
     */
    public suspend fun applyReceipt(contactId: String, serverMessageId: String, kind: ReceiptKind): UUID? =
        db {
            val row =
                query("SELECT client_message_id, delivery_state FROM message WHERE contact_id = ? AND server_message_id = ?", contactId, serverMessageId) {
                    UUID.fromString(it.getText(0)) to DeliveryState.fromRawValue(it.getText(1))
                }.firstOrNull() ?: return@db null
            val current = row.second ?: return@db null
            if (!current.canTransitionTo(kind.deliveryState)) return@db null
            execute("UPDATE message SET delivery_state = ? WHERE client_message_id = ?", kind.deliveryState.rawValue, row.first.toString())
            row.first
        }

    /**
     * One page of transcript, **oldest-first within the page**, walking backwards from [before]
     * (exclusive) or from the newest message when [before] is `null`. `hasMore` reports whether anything
     * older exists, so a UI can hide its "load earlier" affordance exactly once.
     */
    public suspend fun messages(contactId: String, before: UUID?, limit: Int): TranscriptSlice =
        db {
            val pageSize = limit.coerceIn(1, MAX_PAGE)
            var skipped = 0
            fun readable(rows: List<Row>): List<StoredMessage> =
                rows.mapNotNull { row -> project(row).also { if (it == null) skipped += 1 } }

            // Cursor → where to page back from. A cursor that is an unreconciled echo (no sequence)
            // means "everything the server has acknowledged", and a cursor that no longer exists is
            // reported as exhausted rather than silently restarting at the newest page.
            var upper: Pair<Long, String>? = null
            if (before != null) {
                val cursor = rowById(contactId, before)
                if (cursor == null) {
                    undecryptableRowsSkipped = 0
                    return@db TranscriptSlice(emptyList(), false)
                }
                val cursorSequence = cursor.sequence
                if (cursorSequence != null) {
                    upper = cursorSequence to cursor.clientMessageId.toString()
                } else {
                    val tail = unsequencedRows(contactId)
                    val end = tail.indexOfFirst { it.clientMessageId == before }.let { if (it < 0) tail.size else it }
                    // Fill from readable rows only: bounding by index would return an empty page when a
                    // window of echoes is unreadable, and the pager's cursor would not move.
                    val page = ArrayList<StoredMessage>()
                    var index = end
                    while (index > 0 && page.size < pageSize) {
                        index -= 1
                        project(tail[index])?.let(page::add) ?: run { skipped += 1 }
                    }
                    page.reverse()
                    val olderSequenced = sequencedCount(contactId)
                    // An entirely unreadable echo window would stall the pager, so fall through to the
                    // sequenced region instead.
                    if (page.isNotEmpty() || olderSequenced == 0L) {
                        undecryptableRowsSkipped = skipped
                        return@db TranscriptSlice(page, index > 0 || olderSequenced > 0)
                    }
                    // Otherwise cross into the sequenced region, from its newest row.
                }
            }

            // The unsequenced tail is the newest content — the outbox — and belongs on the newest page.
            // Reserve its space first: filling with sequenced rows and appending whatever fits would
            // drop a pending message from a cold open entirely.
            var tail: List<StoredMessage> = emptyList()
            var tailOverflowed = false
            if (before == null) {
                val all = readable(unsequencedRows(contactId))
                tail = all.takeLast(pageSize)
                tailOverflowed = all.size > tail.size
            }
            val sequencedWanted = (pageSize - tail.size).coerceAtLeast(0)

            // Collect one more readable row than needed — the only signal `hasMore` requires — and keep
            // going while rows are unreadable, so a damaged window never yields an empty page.
            val batchSize = ((sequencedWanted + 1) * 2).coerceAtLeast(2)
            val kept = ArrayList<StoredMessage>()
            var cursor = upper
            while (kept.size <= sequencedWanted) {
                val batch = sequencedRows(contactId, below = cursor, limit = batchSize)
                if (batch.isEmpty()) break
                val last = batch.last()
                cursor = (last.sequence ?: 0L) to last.clientMessageId.toString()
                for (row in batch) {
                    val m = project(row)
                    if (m == null) {
                        skipped += 1
                        continue
                    }
                    kept += m
                    if (kept.size > sequencedWanted) break
                }
                if (batch.size < batchSize) break // exhausted
            }
            // `hasMore` is also true when the outbox itself overflowed the page, or a UI that pages only
            // while it is true could never reach the older unsent messages.
            val hasMore = kept.size > sequencedWanted || tailOverflowed
            undecryptableRowsSkipped = skipped
            TranscriptSlice(kept.take(sequencedWanted).reversed() + tail, hasMore)
        }

    /**
     * Messages in transcript order: by server sequence, then local echoes (no sequence) pinned to the
     * tail in composition order. `createdAt` is never an ordering key except, after `localOrder`, as a
     * deterministic tiebreak; the client id breaks any remaining tie.
     */
    public suspend fun messages(contactId: String): List<StoredMessage> =
        db {
            var skipped = 0
            // One damaged row must not take the whole transcript down: skip it and count it, matching
            // the fail-safe posture of the participant blob. The server holds the authoritative copy.
            val out =
                query("SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? ORDER BY $TRANSCRIPT_ORDER", contactId, mapper = ::row)
                    .mapNotNull { project(it).also { m -> if (m == null) skipped += 1 } }
            undecryptableRowsSkipped = skipped
            out
        }

    /**
     * The outbox: outbound messages the server has not acknowledged, in composition order. A row whose
     * body can no longer be read is skipped here and failed by the caller — it can never be sent, and
     * leaving it in the queue would block everything behind it. Not counted as a transcript skip.
     */
    public suspend fun pendingOutbox(contactId: String): List<StoredMessage> = db { pendingRows(contactId).mapNotNull { project(it) } }

    /**
     * The outbox in one read: the sendable rows in composition order, and the ids of pending rows
     * whose body can no longer be read. One read, because two would race a concurrent append — a row
     * added between them would look unreadable and be failed.
     */
    public suspend fun outboxSnapshot(contactId: String): OutboxSnapshot =
        db {
            val rows = pendingRows(contactId)
            val sendable = ArrayList<StoredMessage>()
            val unreadable = ArrayList<UUID>()
            for (row in rows) project(row)?.let(sendable::add) ?: unreadable.add(row.clientMessageId)
            OutboxSnapshot(sendable, unreadable)
        }

    /** See [outboxSnapshot]. */
    public data class OutboxSnapshot(val sendable: List<StoredMessage>, val unreadable: List<UUID>)

    /** Client ids of every pending outbound row, readable or not. */
    public suspend fun pendingClientIds(contactId: String): List<UUID> = db { pendingRows(contactId).map { it.clientMessageId } }

    /** `true` while [clientMessageId] is still an unacknowledged outbound row. */
    public suspend fun isPending(clientMessageId: UUID): Boolean =
        db {
            exists(
                "SELECT 1 FROM message WHERE client_message_id = ? AND direction = ? AND delivery_state = ? AND server_message_id IS NULL",
                clientMessageId.toString(),
                Direction.OUTBOUND.rawValue,
                DeliveryState.SENDING.rawValue,
            )
        }

    /**
     * Returns `false` when the row was already in [next] (a retransmitted receipt, or an ack that
     * arrives after the server echo already adopted the row): nothing changed, so emit nothing.
     */
    public suspend fun updateDeliveryState(clientMessageId: UUID, next: DeliveryState, serverMessageId: String? = null, sequence: Long? = null): Boolean =
        db {
            val (contactId, raw) =
                query("SELECT contact_id, delivery_state FROM message WHERE client_message_id = ?", clientMessageId.toString()) {
                    it.getText(0) to it.getText(1)
                }.firstOrNull() ?: throw TouchpointException.ContactNotFound()
            val current = DeliveryState.fromRawValue(raw) ?: throw TouchpointException.MalformedPayload("unknown delivery state $raw")
            if (current == next) return@db false // idempotent no-op (m3)
            if (!current.canTransitionTo(next)) {
                throw TouchpointException.MalformedPayload("illegal delivery transition ${current.rawValue} → ${next.rawValue}")
            }
            transaction {
                execute("UPDATE message SET delivery_state = ? WHERE client_message_id = ?", next.rawValue, clientMessageId.toString())
                if (serverMessageId != null) {
                    execute("UPDATE message SET server_message_id = ? WHERE client_message_id = ?", serverMessageId, clientMessageId.toString())
                }
                if (sequence != null) {
                    execute("UPDATE message SET sequence = ? WHERE client_message_id = ?", sequence, clientMessageId.toString())
                    raiseLastSequence(contactId, sequence)
                }
            }
            true
        }

    /**
     * Forces the SQLite write-ahead log into the main file. Tests need it so the raw-file ciphertext
     * assertion is not fooled by a fresh write still sitting in the WAL.
     */
    public suspend fun checkpoint() {
        db { prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() } }
    }

    /** `false` once [close] has run. */
    public val isOpen: Boolean get() = connection != null

    /** Closes the database. The store is unusable afterwards. */
    public suspend fun close() {
        withContext(io) {
            mutex.withLock {
                connection?.close()
                connection = null
            }
        }
    }

    // Test seams

    internal suspend fun corruptCiphertextForTesting(clientMessageId: UUID) {
        db { execute("UPDATE message SET body_ciphertext = ? WHERE client_message_id = ?", ByteArray(40) { 0xAB.toByte() }, clientMessageId.toString()) }
    }

    /** The framework driver's pooled connections may not have foreign keys on; the store must not need them. */
    internal suspend fun withForeignKeysOffForTesting() {
        db { execute("PRAGMA foreign_keys = OFF") }
    }

    internal suspend fun totalMessageRowCount(): Long = db { query("SELECT COUNT(*) FROM message") { it.getLong(0) }.first() }

    internal suspend fun corruptParticipantsBlobForTesting(contactId: String) {
        db { execute("UPDATE conversation SET participants_json = ? WHERE contact_id = ?", "{not json", contactId) }
    }

    /** Moves one row's ciphertext onto another, as an attacker editing the file would. */
    internal suspend fun swapCiphertextForTesting(from: UUID, to: UUID) {
        db {
            val blob = query("SELECT body_ciphertext FROM message WHERE client_message_id = ?", from.toString()) { it.getBlob(0) }.first()
            execute("UPDATE message SET body_ciphertext = ? WHERE client_message_id = ?", blob, to.toString())
        }
    }

    // Internals

    private class Row(
        val clientMessageId: UUID,
        val serverMessageId: String?,
        val sequence: Long?,
        val ciphertext: ByteArray,
        val direction: String,
        val participantRole: String,
        val participantId: String?,
        val createdAt: Long,
        val deliveryState: String,
        val interactive: ByteArray?,
        val citations: ByteArray?,
        val isStreaming: Boolean,
        val attachmentId: String?,
        val attachmentType: String?,
        val attachmentSize: Long?,
    )

    @Serializable
    private class ParticipantJson(val id: String, val role: String, val displayName: String)

    private val NAMES: kotlinx.serialization.KSerializer<Map<String, String>> =
        kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

    private suspend fun <T> db(block: SQLiteConnection.() -> T): T =
        withContext(io) {
            mutex.withLock {
                val c = connection ?: throw TouchpointException.PersistenceFailure("store is closed")
                try {
                    c.block()
                } catch (e: TouchpointException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw TouchpointException.PersistenceFailure(e.javaClass.simpleName)
                }
            }
        }

    private fun connect(schema: StoreSchema): SQLiteConnection {
        val c = driver.open(databaseFile.path)
        try {
            c.prepare("PRAGMA journal_mode = WAL").use { it.step() }
            c.execute("PRAGMA foreign_keys = ON")
            schema.migrate(c)
            // Touch the store so a corrupted file fails here, inside the rebuild guard, not on first use.
            c.prepare("SELECT COUNT(*) FROM conversation").use { it.step() }
        } catch (e: Exception) {
            c.close()
            throw e
        }
        return c
    }

    private fun SQLiteConnection.pendingRows(contactId: String): List<Row> =
        query(
            "SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? AND direction = ? AND delivery_state = ? AND server_message_id IS NULL " +
                "ORDER BY local_order, created_at, client_message_id",
            contactId,
            Direction.OUTBOUND.rawValue,
            DeliveryState.SENDING.rawValue,
            mapper = ::row,
        )

    private fun SQLiteConnection.unsequencedRows(contactId: String): List<Row> =
        query(
            "SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? AND sequence IS NULL ORDER BY local_order, created_at, client_message_id",
            contactId,
            mapper = ::row,
        )

    /**
     * Rows the server has ordered, newest first, strictly below the cursor. The cursor is the pair
     * `(sequence, clientMessageId)`, the same tiebreak the full read uses: two messages can share a
     * server millisecond, and a cursor on the sequence alone would skip the second one.
     */
    private fun SQLiteConnection.sequencedRows(contactId: String, below: Pair<Long, String>?, limit: Int): List<Row> =
        if (below == null) {
            query(
                "SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? AND sequence IS NOT NULL ORDER BY sequence DESC, client_message_id DESC LIMIT ?",
                contactId,
                limit,
                mapper = ::row,
            )
        } else {
            query(
                "SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? AND sequence IS NOT NULL " +
                    "AND (sequence < ? OR (sequence = ? AND client_message_id < ?)) ORDER BY sequence DESC, client_message_id DESC LIMIT ?",
                contactId,
                below.first,
                below.first,
                below.second,
                limit,
                mapper = ::row,
            )
        }

    /** Throws rather than reporting zero: a failed count read as "nothing older" would hide the history. */
    private fun SQLiteConnection.sequencedCount(contactId: String): Long =
        query("SELECT COUNT(*) FROM message WHERE contact_id = ? AND sequence IS NOT NULL", contactId) { it.getLong(0) }.first()

    private fun SQLiteConnection.rowById(contactId: String, clientMessageId: UUID): Row? =
        query("SELECT $ROW_COLUMNS FROM message WHERE contact_id = ? AND client_message_id = ?", contactId, clientMessageId.toString(), mapper = ::row)
            .firstOrNull()

    private fun SQLiteConnection.raiseLastSequence(contactId: String, sequence: Long?) {
        if (sequence != null) execute("UPDATE conversation SET last_sequence = MAX(last_sequence, ?) WHERE contact_id = ?", sequence, contactId)
    }

    private fun row(st: SQLiteStatement): Row =
        Row(
            clientMessageId = UUID.fromString(st.getText(0)),
            serverMessageId = st.textOrNull(1),
            sequence = if (st.isNull(2)) null else st.getLong(2),
            ciphertext = st.getBlob(3),
            direction = st.getText(4),
            participantRole = st.getText(5),
            participantId = st.textOrNull(6),
            createdAt = st.getLong(7),
            deliveryState = st.getText(8),
            interactive = st.blobOrNull(9),
            citations = st.blobOrNull(10),
            isStreaming = st.getLong(11) != 0L,
            attachmentId = st.textOrNull(12),
            attachmentType = st.textOrNull(13),
            attachmentSize = if (st.isNull(14)) null else st.getLong(14),
        )

    /** The stored value, or `null` when its body can no longer be read. */
    private fun project(r: Row): StoredMessage? {
        val body = tryDecrypt(r) ?: return null
        return StoredMessage(
            clientMessageId = r.clientMessageId,
            serverMessageId = r.serverMessageId,
            body = body,
            direction = Direction.fromRawValue(r.direction) ?: Direction.INBOUND,
            participantRole = ParticipantRole.fromRawValue(r.participantRole) ?: ParticipantRole.SYSTEM,
            participantId = r.participantId,
            sequence = r.sequence,
            createdAt = Instant.ofEpochMilli(r.createdAt),
            deliveryState = DeliveryState.fromRawValue(r.deliveryState) ?: DeliveryState.FAILED,
            interactiveJson = r.interactive?.let { open(it, fieldAad(r.clientMessageId, "interactive")) },
            citationsJson = r.citations?.let { open(it, fieldAad(r.clientMessageId, "citations")) },
            isStreaming = r.isStreaming,
            attachmentId = r.attachmentId,
            attachmentContentType = r.attachmentType,
            attachmentSize = r.attachmentSize,
        )
    }

    private fun record(st: SQLiteStatement): ConversationRecord {
        val humanAgentPresent = st.getLong(4) != 0L
        // Fail SAFE (C7): a corrupt blob must not read as "no agent present", because the Live Sync dispatch
        // gate keys off it. Reconstruct the one fact we still have from the flag.
        val participants =
            decodeParticipants(st.getText(3))
                ?: if (humanAgentPresent) ParticipantSet().join(Participant("unknown-agent", ParticipantRole.AGENT, "")) else ParticipantSet()
        return ConversationRecord(
            contactId = st.getText(0),
            modality = Modality.fromRawValue(st.getText(1)) ?: Modality.CHAT,
            lastSequence = st.getLong(2),
            participants = participants,
            humanAgentPresent = humanAgentPresent,
            lastActivity = Instant.ofEpochMilli(st.getLong(5)),
            participantNames = openNames(st.getText(0), st.blobOrNull(6)),
        )
    }

    /** Names are personal data, sealed as a body is. Unreadable, none: a name is not worth a failure. */
    private fun openNames(
        contactId: String,
        sealed: ByteArray?,
    ): Map<String, String> = sealed?.let { open(it, namesAad(contactId)) }?.let { ignoringFailure { json.decodeFromString(NAMES, it) } }.orEmpty()

    private fun namesAad(contactId: String): String = "conversation/$contactId/names"

    private fun SQLiteStatement.blobOrNull(i: Int): ByteArray? = if (isNull(i)) null else getBlob(i)

    private fun encodeParticipants(set: ParticipantSet): String =
        // Who, by id and role — what the dispatch gate reads, in the clear so a lost key still fails safe.
        // Their names go sealed, with the rest (participant_names_ciphertext).
        json.encodeToString(ListSerializer(ParticipantJson.serializer()), set.participants.map { ParticipantJson(it.id, it.role.rawValue, "") })

    private fun decodeParticipants(text: String): ParticipantSet? =
        ignoringFailure {
            json.decodeFromString(ListSerializer(ParticipantJson.serializer()), text).fold(ParticipantSet()) { set, p ->
                // An unrecognised stored role reads as UNKNOWN, which counts as human: fail safe.
                set.join(Participant(p.id, ParticipantRole.fromRawValue(p.role) ?: ParticipantRole.UNKNOWN, p.displayName))
            }
        }

    /** `iv | ciphertext+tag`, with the row's client id as associated data. */
    private fun encrypt(plaintext: String, clientMessageId: UUID): ByteArray = seal(plaintext, clientMessageId.toString())

    private fun tryDecrypt(r: Row): String? = open(r.ciphertext, r.clientMessageId.toString())

    /** A row's other sealed fields, each bound to its own row and field: one cannot stand in for another. */
    private fun fieldAad(
        clientMessageId: UUID,
        field: String,
    ): String = "$clientMessageId/$field"

    /** `iv | ciphertext+tag`, with [aad] as associated data. */
    private fun seal(
        plaintext: String,
        aad: String,
    ): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad.encodeToByteArray())
        val ct = cipher.doFinal(plaintext.encodeToByteArray())
        return ByteBuffer.allocate(iv.size + ct.size).put(iv).put(ct).array()
    }

    private fun open(
        blob: ByteArray,
        aad: String,
    ): String? {
        if (blob.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            cipher.updateAAD(aad.encodeToByteArray())
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}

private const val ROW_COLUMNS =
    "client_message_id, server_message_id, sequence, body_ciphertext, direction, participant_role, participant_id, " +
        "created_at, delivery_state, interactive_ciphertext, citations_ciphertext, is_streaming, attachment_id, attachment_type, attachment_size"

/** Sequenced rows by server order, then the unsequenced tail in composition order; the client id breaks any tie. */
private const val TRANSCRIPT_ORDER =
    "(sequence IS NULL), sequence, CASE WHEN sequence IS NULL THEN local_order END, CASE WHEN sequence IS NULL THEN created_at END, client_message_id"
