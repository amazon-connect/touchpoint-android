package com.amazon.connect.touchpoint.chat

/**
 * A custom chat client that can also mint the listen-only socket sign-in needs: the host's
 * backend calls `CreateParticipantConnection` with `Type: ["WEBSOCKET"]` only — no connection
 * credentials, so the session's connection token is not rotated — with the participant token it
 * holds, and returns the socket URL. Implement it on the `AWSClient` subclass given to
 * `ConnectChatTransport.live(…, customChatClient)` when the chat offers sign-in.
 */
public interface TouchpointSignInSocketProviding {
    /** A fresh URL for a socket that hears the contact's events, minted by the host's backend. */
    public suspend fun signInEventsWebSocketUrl(): String
}
