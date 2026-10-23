package com.wkq.photo.imagerender

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

sealed interface ImageSource {
    val displayName: String

    fun openFileDescriptor(): ParcelFileDescriptor

    data class FileSource(val file: File) : ImageSource {
        override val displayName: String = file.name

        override fun openFileDescriptor(): ParcelFileDescriptor {
            if (!file.isFile) throw FileNotFoundException("Image source does not exist: ${file.absolutePath}")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    data class UriSource(
        val contentResolver: ContentResolver,
        val uri: Uri,
        override val displayName: String = uri.lastPathSegment ?: "content"
    ) : ImageSource {
        override fun openFileDescriptor(): ParcelFileDescriptor =
            contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Unable to open image URI: $uri")
    }

    data class ParcelFileDescriptorSource(
        val descriptor: ParcelFileDescriptor,
        override val displayName: String = "descriptor"
    ) : ImageSource {
        override fun openFileDescriptor(): ParcelFileDescriptor =
            ParcelFileDescriptor.dup(descriptor.fileDescriptor)
    }
}

data class BitmapDecodeOptions(
    val maxWidth: Int = Int.MAX_VALUE,
    val maxHeight: Int = Int.MAX_VALUE,
    val mutable: Boolean = false
) {
    init { require(maxWidth > 0 && maxHeight > 0) }
}
