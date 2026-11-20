package com.acmebank.demo.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acmebank.demo.Palette
import com.acmebank.demo.R
import com.amazon.connect.touchpoint.chat.ui.TouchpointModalityContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `AcmeDisputeSummary`, the showcase flow's own modality — the dispute about to be raised — drawn by
 * the bank: the SDK hands over the modality's JSON, web's `customModalities`.
 */
val AcmeModalities: Map<String, TouchpointModalityContent> =
    mapOf("AcmeDisputeSummary" to { modality, _ -> DisputeSummary(modality.data as? JsonObject) })

@Composable
private fun DisputeSummary(data: JsonObject?) {
    fun text(key: String) = (data?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    Column(
        Modifier.widthIn(max = 320.dp).fillMaxWidth()
            .background(Palette.brand.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.dispute_summary), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = Palette.brand)
        text("merchant")?.let { Line(stringResource(R.string.dispute_merchant), it) }
        text("amount")?.let { Line(stringResource(R.string.dispute_amount), it) }
        text("status")?.let { Line(stringResource(R.string.dispute_status), it) }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        // The chat's own ink, light or dark as the chat is: the bank's ink alone vanishes on a dark panel.
        val chat = Palette.chatTheme()
        Text(label, style = MaterialTheme.typography.bodyMedium, color = chat.primary60)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = chat.primary)
    }
}
