package com.acmebank.demo

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color

/**
 * The demo's brand: Acme Bank's, or a partner's when `demo/acme-bank/partner`
 * exists — the build compiles that folder in, and `brand-acme` otherwise, so a partner's brand goes
 * with the folder. It names the bank across the app (the sign-in screen, the credit card, the
 * notifications, the call, the Live Sync consent, the offline agent, the launcher's name) and gives
 * the partner bank look its mark, title and assistant.
 *
 * @param tint The app's tint and primary-action colour; `null` keeps Acme's teal.
 * @param headerMark The chat header's brand image; `null` is Acme's drawn mark.
 * @param assistantAvatar The assistant's avatar in participant info; `null` is Acme's drawn mark.
 */
class PartnerBrand(
    val bankName: String,
    val bankShortName: String,
    val chatTitle: String,
    val assistantName: String,
    val tint: Color? = null,
    @DrawableRes val headerMark: Int? = null,
    @DrawableRes val assistantAvatar: Int? = null,
) {
    companion object {
        val acme = PartnerBrand(bankName = "Acme Bank", bankShortName = "Acme", chatTitle = "Chat with Acme", assistantName = "Acme Assistant")

        /** The partner folder's brand where it is compiled in, else Acme's. */
        val current: PartnerBrand get() = PartnerFolder.brand ?: acme
    }
}
