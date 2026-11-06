package com.amazon.connect.touchpoint.chat.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract
import com.amazon.connect.touchpoint.core.Attachment
import com.amazon.connect.touchpoint.core.AttachmentUpload
import java.io.IOException

/**
 * Files in and out, through the system's own pickers — no storage permission, nothing written to
 * shared storage by the SDK, and no provider declared.
 */
internal object AttachmentFiles {
    /**
     * Web's `accept` list as MIME types: .jpg/.jpeg, .png, .webp, .mp4/.mpeg4, .avi, .mov, .pdf. Which
     * of them Connect takes is the instance's own setting; one it refuses fails as an upload.
     */
    val TYPES: Array<String> = arrayOf("image/jpeg", "image/png", "image/webp", "video/mp4", "video/x-msvideo", "video/quicktime", "application/pdf")

    /**
     * The picked file as an upload — its name, type and size from the provider, read when the upload
     * opens it. `null` when the provider will not say what it is.
     */
    fun describe(
        resolver: ContentResolver,
        uri: Uri,
    ): AttachmentUpload? {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (_: RuntimeException) {
            // A provider that cannot be queried: what the path and the descriptor tell is all there is.
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val bytes =
            size ?: try {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            } ?: return null
        val type =
            resolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
                ?: Attachment.UNKNOWN_TYPE_NAME
        return AttachmentUpload(fileName, type, bytes) { resolver.openInputStream(uri) ?: throw IOException("unreadable") }
    }

    /** "Save to…": the system's own, so the customer chooses where an agent's file goes. */
    object SaveAs : ActivityResultContract<Attachment, Uri?>() {
        override fun createIntent(
            context: android.content.Context,
            input: Attachment,
        ): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.contentType.ifBlank { Attachment.UNKNOWN_TYPE_NAME })
                .putExtra(Intent.EXTRA_TITLE, input.name)

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): Uri? = intent?.data.takeIf { resultCode == android.app.Activity.RESULT_OK }
    }
}
