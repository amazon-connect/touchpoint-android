package com.amazon.connect.touchpoint.core

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.UUID

/**
 * The Amazon Connect contact id of the live call Live Sync should bind to.
 *
 * A contact the SDK started — a chat, an in-app call — needs none: the session has its id. This is
 * for a call made outside the app, from the phone's dialler, that the app must find.
 *
 * **In production, resolve through the host's backend.** It knows which signed-in customer is calling
 * and can hand the app the id over its own authenticated channel, bound to an identity it has
 * verified. [DeepLinkContactIdResolver] is a reference, with the caveat on it.
 */
public fun interface TouchpointContactIdResolver {
    /** The id, or `null` when there is none to bind to yet. Throw for a failure the host should hear of. */
    public suspend fun resolveContactId(): String?
}

/** A fixed id: for a contact the SDK started, or one typed in for a demo. */
public class StaticContactIdResolver(private val contactId: String?) : TouchpointContactIdResolver {
    override suspend fun resolveContactId(): String? = contactId
}

/**
 * The id from a deep link the app was opened with.
 *
 * **A deep link with no authentication is a bearer credential for the conversation:** whoever holds
 * the URL can bind an app to that call and receive every action the assistant takes in it. The
 * backend that makes such links must bind them to the signed-in customer and expire them, and the app
 * must know the link reached it by a channel only that customer receives. For a bank, resolve through
 * the backend; this is for development and the demo.
 *
 * Accepted, with the scheme and host matched exactly:
 * - `<scheme>://<host>/<path>/<contactId>`, the id as the last path segment;
 * - `<scheme>://<host>/<path>?contactId=<contactId>` (or `contact_id`), which wins over the path.
 *
 * The id must be a UUID, as Connect's are, so a hostile URL cannot put other text into the socket's
 * query. Anything else is `null`, and nothing is logged.
 *
 * @param pathPrefix the path that must come before the id (`/livesync`); `null` accepts any.
 */
public class DeepLinkContactIdResolver(
    private val url: String?,
    private val scheme: String,
    private val host: String,
    private val pathPrefix: String? = null,
) : TouchpointContactIdResolver {
    override suspend fun resolveContactId(): String? = url?.let { contactId(it, scheme, host, pathPrefix) }

    public companion object {
        /** The parse, pure: `null` for anything but an exact scheme and host carrying a well-formed id. */
        public fun contactId(
            url: String,
            scheme: String,
            host: String,
            pathPrefix: String? = null,
        ): String? {
            val uri =
                try {
                    URI(url)
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!uri.scheme.equals(scheme, ignoreCase = true) || !uri.host.equals(host, ignoreCase = true)) return null
            if (uri.rawUserInfo != null || uri.port != -1) return null
            val path = uri.rawPath.orEmpty()
            val segments = path.split('/').filter { it.isNotEmpty() }
            // No relative segments: a link that needs normalising to match was not our backend's.
            if (segments.any { it == "." || it == ".." }) return null
            if (pathPrefix != null) {
                val prefix = if (pathPrefix.startsWith("/")) pathPrefix else "/$pathPrefix"
                if (path != prefix && !path.startsWith("$prefix/")) return null
            }
            // An explicit contactId= beats a guess from the path.
            val query =
                uri.rawQuery?.split('&')?.mapNotNull { part ->
                    val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                    if (k.lowercase() in QUERY_KEYS) v else null
                }?.firstOrNull()
            if (query != null) {
                // A malformed escape is not an id: refused, as anything else is, never thrown.
                val decoded =
                    try {
                        URLDecoder.decode(query, Charsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                return validated(decoded)
            }
            return validated(segments.lastOrNull())
        }

        private val QUERY_KEYS = setOf("contactid", "contact_id")

        /** Connect's contact ids are UUIDs; anything else is refused. */
        internal fun validated(candidate: String?): String? {
            if (candidate == null || candidate.length != UUID_LENGTH) return null
            return try {
                UUID.fromString(candidate).toString().takeIf { it.equals(candidate, ignoreCase = true) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private const val UUID_LENGTH = 36
    }
}
