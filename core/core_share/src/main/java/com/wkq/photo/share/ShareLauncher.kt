package com.wkq.photo.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

data class ShareRequest(
    val uris: List<Uri>,
    val chooserTitle: String,
    val mimeType: String = "*/*"
) {
    init {
        require(uris.isNotEmpty())
        require(uris.all { it.scheme == ContentScheme }) { "Only content URIs can be shared" }
    }

    private companion object {
        const val ContentScheme = "content"
    }
}

/** 统一分享授权与 ClipData，调用方不再自行拼装 ACTION_SEND Intent。 */
object ShareLauncher {

    fun launch(context: Context, request: ShareRequest) {
        val sendIntent = if (request.uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = request.mimeType
                putExtra(Intent.EXTRA_STREAM, request.uris.single())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = request.mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(request.uris))
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("shared-file", request.uris.first()).also { clip ->
                request.uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        val chooser = Intent.createChooser(sendIntent, request.chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
