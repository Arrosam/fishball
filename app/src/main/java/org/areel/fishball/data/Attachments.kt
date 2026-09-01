package org.areel.fishball.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.areel.fishball.core.llm.LlmContent
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A picture on its way from the gallery or the camera to the model.
 *
 * Three things have to happen to it and they all happen here: it is decoded at a size worth
 * sending, turned the right way up, and re-encoded as JPEG. A phone camera writes eight to
 * twelve megapixels; the model is being asked to read a medicine box, and the difference
 * between that and [MAX_EDGE] is bytes uploaded over somebody's mobile data for detail no
 * answer will ever use.
 */
class Attachments(private val context: Context) {

    /**
     * Read [uri] and shrink it. Null if it cannot be decoded at all — a file that is not really
     * an image, or one the picker handed us a permission for that has already lapsed.
     */
    suspend fun read(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        runCatching {
            // Two passes. The first reads only the header, so a twelve-megapixel photo never
            // has to exist in memory at full size just to find out how big it is.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                // Powers of two only, which is all inSampleSize honours; the exact fit comes
                // from the scale below.
                var step = 1
                while (longest / (step * 2) >= MAX_EDGE) step *= 2
                inSampleSize = step
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return@runCatching null

            val scaled = fit(decoded)
            val upright = turnUpright(uri, scaled)

            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            // Measured before it is freed. Reading a recycled bitmap's size is undefined, and
            // the two lines below were doing exactly that.
            val w = upright.width
            val h = upright.height
            upright.recycle()

            val bytes = out.toByteArray()
            Attachment(
                uri = uri,
                // Decoded back out of the JPEG that is about to be sent, so the square in the
                // composer is literally the picture the model will see. A library to fetch the
                // Uri a second time would be a dependency for one 46dp thumbnail.
                thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size),
                width = w,
                height = h,
                bytes = bytes.size,
                content = LlmContent.Image(
                    mediaType = "image/jpeg",
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                ),
            )
        }.getOrNull()
    }

    /** Somewhere for the camera to write to, handed out through the same provider updates uses. */
    fun cameraTarget(): Pair<File, Uri> {
        val dir = File(context.cacheDir, "shots").apply { mkdirs() }
        // Named per shot. A fixed name meant two photographs in one message shared a Uri,
        // and the row could then not tell one from the other.
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        // The same provider the updater uses. One authority, two directories, both scoped in
        // res/xml/update_paths.xml - a second provider for one more folder would be ceremony.
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.updates",
            file,
        )
        return file to uri
    }

    private fun fit(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Phones record which way they were held rather than rotating the pixels, so a photo taken
     * in portrait arrives on its side. A model asked to read a label sideways will read it
     * sideways.
     */
    private fun turnUpright(uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap

        val turned = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private companion object {
        /** Long edge. Enough to read a label off a box, and a fraction of what a camera writes. */
        const val MAX_EDGE = 1280
        const val QUALITY = 82
    }
}

/**
 * A picture waiting to be sent, and the one thing the model needs from it.
 *
 * [uri] is kept only to tell one attachment from another; the composer draws [thumb].
 */
data class Attachment(
    val uri: Uri,
    /** For the composer's square. Small already - it is the shrunk image, not the original. */
    val thumb: Bitmap?,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val content: LlmContent.Image,
)
