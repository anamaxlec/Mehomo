package dev.memoh.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Explicit user choice: downscale to 2048px, retaining orientation and alpha. */
internal fun compressImage(bytes: ByteArray): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解析图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    var bitmap = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize(sample)
    } else {
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "无法解析图片" }
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }
    val scale = 2048f / maxOf(bitmap.width, bitmap.height)
    if (scale < 1) {
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    return try {
        ByteArrayOutputStream().use { output ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            check(bitmap.compress(format, 85, output)) { "图片压缩失败" }
            output.toByteArray()
        }
    } finally { bitmap.recycle() }
}
