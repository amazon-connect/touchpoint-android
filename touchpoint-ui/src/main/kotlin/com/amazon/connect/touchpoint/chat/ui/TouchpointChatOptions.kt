@file:OptIn(InternalTouchpointUi::class)

package com.amazon.connect.touchpoint.chat.ui

import androidx.compose.runtime.ProvidableCompositionLocal

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp

/**
 * Touchpoint web's customization options (`TouchpointConfiguration`), in Android's terms, with the
 * Touchpoint design guide's defaults (web's, where the guide does not differ): a host that sets the
 * same options gets the same chat. Colours, the font and the two radii
 * are the theme's ([TouchpointTheme]); the words, string resources (any `touchpoint_*` a host
 * redefines wins, Android's form of web's `copy`).
 *
 * Not here, the host owning the container: web's `windowSize`, `containerStyle`, `zIndex*`,
 * `launchIcon`.
 *
 * Not a data class, as [TouchpointTheme] is not: a new option must not break a host's calls. Equal
 * when every option is, so options built inline do not redraw the chat on each recomposition (the
 * painters compare as the host's painters do — remember them).
 *
 * @param userMessageBubble The customer's messages in a bubble (web: on).
 * @param agentMessageBubble A human agent's messages in the guide's bubble, `secondary40`, as wide as
 *   they read (the guide: on; web's applies to the assistant too, off).
 * @param assistantMessageBubble The assistant's messages in that bubble too (the guide: off, its
 *   words on the background).
 * @param userMessageBubbleStyle Web's `userMessageBubbleStyle`, typed; where the bubble is on.
 * @param agentMessageBubbleStyle Web's `agentMessageBubbleStyle`, typed; where the bubble is on.
 * @param showParticipantInfo Every sender's avatar and name — the customer's, the assistant's and
 *   agents' — over each run of their messages, the customer's still to the end (the guide; web: off).
 * @param agentParticipantInfo A human agent's avatar and name over their runs even without
 *   [showParticipantInfo]: the guide's default agent message is avatar, name and bubble.
 * @param showParticipantNames With participant info, the name beside the avatar over the run; `false`
 *   puts the avatar beside each message instead, and names no one (the guide's two forms).
 * @param assistantName The assistant's name in participant info; `null` is web's "AI".
 * @param assistantIcon The assistant's avatar in participant info; `null` is web's assistant glyph.
 * @param avatarShape Round, or square, as web's `avatarShape`.
 * @param brandIcon The brand in the header, and on the welcome screen with [welcomeScreenLogo]; drawn
 *   32 tall and as wide as the image, as the guide's, so a wordmark shows whole (a square one, 32
 *   square). `null`: the guide's assistant sparkle ([TouchpointHiddenElement.HEADER_BRAND]
 *   leaves the header without either).
 * @param welcomeScreen The guide's welcome screen: until the customer sends, the greeting larger and
 *   left-aligned, held with the composer in the middle of the panel. Full-screen and tablet layouts
 *   only: a half-height sheet shows the greeting as the first message.
 * @param welcomeScreenLogo The brand icon above the welcome screen's greeting (the guide: none; web: on).
 *   Web's legacy spelling: `false` is [TouchpointHiddenElement.WELCOME_SCREEN_LOGO] in [hiddenElements].
 * @param backgroundDepthLayer Web's depth layer — sheen, shade, grain — over the background.
 * @param userMessageTimestamp The time under each of the customer's messages, beside its delivery
 *   state (web: off). The hour and minute for today, else the date too, in the device's locale.
 * @param agentMessageTimestamp The time under the assistant's and agents' messages, on its own line
 *   (web: off); within a run, dropped where the next message shows the same time, as web's.
 * @param headerStyle The header bar's fill and colour scheme (web's `headerStyle`).
 * @param hiddenElements Built-in controls to leave out (web's `hiddenElements`).
 * @param addedElements Optional controls to add (web's `addedElements`).
 * @param headerTitle The name in the header, after the brand: `null` is the guide's "AI Assistant"
 *   (the `touchpoint_header_title` string), an empty string none.
 * @param headerNotice A line under the header, centred over a hairline, for words the customer
 *   should see before anything else ("This chat is recorded."). `null` draws none. It is
 *   a host's opt-in; web has no such slot.
 * @param copy The host's words for some of the SDK's (web's `copy`, ported here: the end
 *   confirmation only). A `null` part keeps the SDK's string resource.
 * @param attachmentsEnabled Attach in the composer while an agent is answering (web's
 *   `attachmentsEnabled`, from 1.0.6: off unless the host turns it on).
 * @param icons Replacements for built-in icons (web's `icons`), keyed by web's names. Each is tinted
 *   as the control it sits in, as web's `currentColor`: draw it on a 24 grid, one colour. Icons left
 *   out keep web's.
 */
@Immutable
public class TouchpointChatOptions(
    public val userMessageBubble: Boolean = true,
    public val agentMessageBubble: Boolean = true,
    public val userMessageBubbleStyle: TouchpointBubbleStyle? = null,
    public val agentMessageBubbleStyle: TouchpointBubbleStyle? = null,
    public val showParticipantInfo: Boolean = false,
    public val assistantName: String? = null,
    public val assistantIcon: Painter? = null,
    public val avatarShape: TouchpointAvatarShape = TouchpointAvatarShape.ROUND,
    public val brandIcon: Painter? = null,
    public val welcomeScreen: Boolean = true,
    public val welcomeScreenLogo: Boolean = false,
    public val backgroundDepthLayer: Boolean = true,
    public val userMessageTimestamp: Boolean = false,
    public val agentMessageTimestamp: Boolean = false,
    public val headerStyle: TouchpointHeaderStyle? = null,
    public val hiddenElements: Set<TouchpointHiddenElement> = emptySet(),
    public val addedElements: Set<TouchpointAddedElement> = emptySet(),
    public val icons: Map<TouchpointIcon, ImageVector> = emptyMap(),
    public val headerTitle: String? = null,
    public val assistantMessageBubble: Boolean = false,
    public val agentParticipantInfo: Boolean = true,
    public val showParticipantNames: Boolean = true,
    public val attachmentsEnabled: Boolean = false,
    public val headerNotice: String? = null,
    public val copy: TouchpointChatCopy = TouchpointChatCopy(),
) {
    /** Whether [element] shows: not in [hiddenElements] (nor, for the welcome logo, [welcomeScreenLogo] `false`). */
    public fun shows(element: TouchpointHiddenElement): Boolean =
        element !in hiddenElements && !(element == TouchpointHiddenElement.WELCOME_SCREEN_LOGO && !welcomeScreenLogo)

    private val tokens: List<Any?>
        get() =
            listOf(
                userMessageBubble, agentMessageBubble, userMessageBubbleStyle, agentMessageBubbleStyle, showParticipantInfo, assistantName,
                assistantIcon, avatarShape, brandIcon, welcomeScreen, welcomeScreenLogo, backgroundDepthLayer,
                userMessageTimestamp, agentMessageTimestamp, headerStyle, hiddenElements, addedElements, icons, headerTitle,
                assistantMessageBubble, agentParticipantInfo, showParticipantNames, attachmentsEnabled,
                headerNotice, copy,
            )

    override fun equals(other: Any?): Boolean = other is TouchpointChatOptions && other.tokens == tokens

    override fun hashCode(): Int = tokens.hashCode()

    override fun toString(): String =
        "TouchpointChatOptions(userMessageBubble=$userMessageBubble, agentMessageBubble=$agentMessageBubble, assistantMessageBubble=$assistantMessageBubble, " +
            "showParticipantInfo=$showParticipantInfo, agentParticipantInfo=$agentParticipantInfo, showParticipantNames=$showParticipantNames, " +
            "attachmentsEnabled=$attachmentsEnabled, " +
            "welcomeScreen=$welcomeScreen, backgroundDepthLayer=$backgroundDepthLayer)"

    public companion object {
        /** The guide's defaults. */
        public val Default: TouchpointChatOptions = TouchpointChatOptions()
    }
}

/**
 * Web's `headerStyle`, typed to what Android's header bar carries:
 * - [background] fills the bar (web `backgroundColor`).
 * - [dark] draws the bar's controls in the dark theme's inks, or the light theme's (web
 *   `colorScheme`, which flips the `light-dark()` theme values inside the header). This is how a
 *   dark brand bar gets white controls over a light panel. `null` follows the chat's theme.
 *
 * Web's `color` is the header title's ink; Android's title takes [dark]'s inks, as the controls do.
 * In every window size, as web's code applies it (its docs say floating only).
 */
@Immutable
public class TouchpointHeaderStyle(
    public val background: Color? = null,
    public val dark: Boolean? = null,
) {
    override fun equals(other: Any?): Boolean = other is TouchpointHeaderStyle && other.background == background && other.dark == dark

    override fun hashCode(): Int = 31 * background.hashCode() + dark.hashCode()

    override fun toString(): String = "TouchpointHeaderStyle(background=$background, dark=$dark)"
}

/** A built-in control a host can leave out (web's `HiddenElement`). */
public enum class TouchpointHiddenElement {
    /** The header's Restart (web's `header.restart`). */
    HEADER_RESTART,

    /** The header's Settings (web's `header.settings`): no settings, so no Restart or Download row either. */
    HEADER_SETTINGS,

    /** The brand above the welcome screen's greeting (web's `welcomescreen.logo`). */
    WELCOME_SCREEN_LOGO,

    /** The header's brand — the host's [TouchpointChatOptions.brandIcon], or the guide's sparkle without one. */
    HEADER_BRAND,
}

/** An optional control a host can add (web's `AddedElement`). */
public enum class TouchpointAddedElement {
    /**
     * A Close in the header, after Collapse (web's `header.close`). While the conversation is live
     * it asks first, as web's does from 1.0.6: "Are you sure?", then End conversation ends it and
     * closes the chat, and Cancel leaves both. Once the conversation has ended, or before it starts, it
     * closes the chat at once. Where Collapse drops a sheet to mini, Close closes it outright.
     */
    HEADER_CLOSE,
}

/**
 * The host's words for some of the SDK's: web's `copy` (a `Partial<Copy>`), ported for the end
 * confirmation only. The host owns the translation of what it sets.
 */
@Immutable
public class TouchpointChatCopy(
    public val endConversationConfirm: EndConversationConfirm = EndConversationConfirm(),
) {
    /** Web's `copy.endConversationConfirm`: each `null` keeps the SDK's own words. */
    @Immutable
    public class EndConversationConfirm(
        public val title: String? = null,
        public val body: String? = null,
        public val confirm: String? = null,
        public val cancel: String? = null,
    ) {
        private val tokens: List<String?> get() = listOf(title, body, confirm, cancel)

        override fun equals(other: Any?): Boolean = other is EndConversationConfirm && other.tokens == tokens

        override fun hashCode(): Int = tokens.hashCode()
    }

    override fun equals(other: Any?): Boolean = other is TouchpointChatCopy && other.endConversationConfirm == endConversationConfirm

    override fun hashCode(): Int = endConversationConfirm.hashCode()
}

/**
 * A host row in the settings, after the SDK's own: a text button
 * with [label] and no icon, [prominent] (web's main variant, as the guide puts a call) or quiet. A
 * tap closes the settings, then runs [action]. Web's settings take no host rows.
 */
public class TouchpointSettingsRow(
    public val label: String,
    public val prominent: Boolean = false,
    public val action: () -> Unit,
)

/** Participant info's avatar: fully round, or square, as web's `avatarShape`. */
public enum class TouchpointAvatarShape { ROUND, SQUARE }

/**
 * Web's bubble style (`CustomStyle` on a message bubble), typed: what a bubble can carry. A `null`
 * keeps web's default for that part.
 */
@Immutable
public class TouchpointBubbleStyle(
    public val fill: Color? = null,
    public val textColor: Color? = null,
    public val cornerRadius: Dp? = null,
    public val paddingHorizontal: Dp? = null,
    public val paddingVertical: Dp? = null,
    public val border: BorderStroke? = null,
) {
    private val tokens: List<Any?> get() = listOf(fill, textColor, cornerRadius, paddingHorizontal, paddingVertical, border)

    override fun equals(other: Any?): Boolean = other is TouchpointBubbleStyle && other.tokens == tokens

    override fun hashCode(): Int = tokens.hashCode()
}

@InternalTouchpointUi
public val LocalChatOptions: ProvidableCompositionLocal<TouchpointChatOptions> = staticCompositionLocalOf { TouchpointChatOptions.Default }
