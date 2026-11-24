package com.filmkit.app.camera

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.DataInputStream
import java.io.File

internal object JpegExif {
    private val signature = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0)

    // AndroidX writes the IFDs to a tiny carrier; it never opens the full JPEG/R.
    fun payload(cacheDir: File, width: Int, height: Int, tags: (ExifInterface) -> Unit): ByteArray {
        val carrier = File.createTempFile("exif-", ".jpg", cacheDir)
        try {
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                carrier.outputStream().use { check(pixel.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                pixel.recycle()
            }
            ExifInterface(carrier.absolutePath).apply {
                setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
                setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
                tags(this)
                saveAttributes()
            }
            return DataInputStream(carrier.inputStream().buffered()).use(::readPayload)
        } finally {
            carrier.delete()
        }
    }

    fun readPayload(input: DataInputStream): ByteArray {
        check(input.readUnsignedShort() == 0xffd8)
        while (true) {
            check(input.readUnsignedByte() == 0xff)
            var marker = input.readUnsignedByte()
            while (marker == 0xff) marker = input.readUnsignedByte()
            check(marker != 0xda && marker != 0xd9) { "Missing EXIF segment" }
            val length = input.readUnsignedShort() - 2
            check(length >= 0)
            val segment = ByteArray(length).also(input::readFully)
            if (marker == 0xe1 && segment.size >= signature.size &&
                signature.indices.all { segment[it] == signature[it] }) return segment
        }
    }
}
