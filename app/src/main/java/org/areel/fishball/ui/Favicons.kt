package org.areel.fishball.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Site icons for the source cards.
 *
 * Fetched from each site itself rather than through an icon service. Google's `s2/favicons` and
 * DuckDuckGo's `icons.duckduckgo.com` would both be one line and always return a tidy PNG, and
 * both are unreachable from the mainland — which is where this app is used — besides handing a
 * third party the list of everything its user has been shown.
 *
 * Three paths, in this order, because **Android cannot decode `.ico`** and that is still what
 * most sites serve. `apple-touch-icon.png` is nearly as common and always a PNG.
 *
 * Failures are remembered. A site with no usable icon should cost one round trip per run, not
 * one every time its card is drawn.
 */
object Favicons {

    private val cache = ConcurrentHashMap<String, Holder>()

    private class Holder(val image: ImageBitmap?)

    private val paths = listOf("/apple-touch-icon.png", "/favicon.png", "/favicon.ico")

    suspend fun of(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        cache[host]?.let { return it.image }

        val found = withContext(Dispatchers.IO) {
            paths.firstNotNullOfOrNull { path -> fetch("https://$host$path") }
        }
        cache[host] = Holder(found)
        return found
    }

    private fun fetch(url: String): ImageBitmap? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            // Some hosts serve a placeholder or a 403 to anything that does not look like a
            // browser — the same bot filtering the search instance applies.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        connection.use {
            if (it.responseCode !in 200..299) {
                null
            } else {
                decode(it.inputStream.readBytes())
            }
        }
    } catch (e: Exception) {
        null
    }

    /** PNG, JPEG and friends go to the platform. ICO does not, so ICO is unpacked here. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.size < 8) return null
        return if (bytes.isIco()) {
            decodeIco(bytes)?.asImageBitmap()
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    private fun ByteArray.isIco() = this[0] == 0.toByte() && this[1] == 0.toByte() &&
        this[2] == 1.toByte() && this[3] == 0.toByte()

    /**
     * An ICO, far enough to get a picture out of it.
     *
     * It is a directory of images rather than an image: a six-byte header, sixteen bytes per
     * entry, then the payloads. A payload is either a whole PNG — which the platform can take
     * from here — or a headerless BMP, which is the case that matters. Every mainland site
     * checked served exactly that: 知乎, 百度百科, 澎湃, 药监局, bilibili, all 32-bit BGRA.
     * Without this the icons would appear for who.int and Wikipedia and for nothing the app
     * actually spends its time reading.
     *
     * 32-bit and 8-bit indexed are both handled - the second because older institutional sites
     * still ship them. Anything else falls back to the glyph, as does anything that does not
     * parse; a missing icon is a smaller problem than a wrong one.
     */
    private fun decodeIco(bytes: ByteArray): Bitmap? = try {
        val count = bytes.u16(4)
        // Largest entry, so a card drawn at 3x is not showing a 16px icon stretched.
        val best = (0 until count)
            .map { i -> 6 + 16 * i }
            .maxByOrNull { e -> (bytes[e].toInt() and 0xFF).let { if (it == 0) 256 else it } }

        if (best == null) {
            null
        } else {
            val size = bytes.u32(best + 8)
            val offset = bytes.u32(best + 12)
            when {
                offset + size > bytes.size -> null
                bytes.isPngAt(offset) -> BitmapFactory.decodeByteArray(bytes, offset, size)
                else -> decodeIcoBmp(bytes, offset)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeIcoBmp(bytes: ByteArray, offset: Int): Bitmap? {
        val headerSize = bytes.u32(offset)
        if (headerSize < 40) return null
        val width = bytes.u32(offset + 4)
        // Doubled: the stored height counts the colour rows and the transparency mask beneath.
        val height = bytes.u32(offset + 8) / 2
        val bits = bytes.u16(offset + 14)
        if (width <= 0 || height <= 0 || width > 512 || height > 512) return null

        val pixels = when (bits) {
            32 -> readTrueColour(bytes, offset + headerSize, width, height)
            // Palettised. Older institutional sites still ship these - drugoffice.gov.hk, and
            // government hosts generally, which is a tier this app leans on - and dropping them
            // would leave the glyph showing for exactly the sources that matter most.
            8 -> readPalettised(bytes, offset, headerSize, width, height)
            else -> null
        } ?: return null

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun readTrueColour(bytes: ByteArray, start: Int, width: Int, height: Int): IntArray? {
        if (start + width * height * 4 > bytes.size) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            // Bottom-up, as BMP rows always are.
            val row = (height - 1 - y) * width * 4
            for (x in 0 until width) {
                val p = start + row + x * 4
                val b = bytes[p].toInt() and 0xFF
                val g = bytes[p + 1].toInt() and 0xFF
                val r = bytes[p + 2].toInt() and 0xFF
                val a = bytes[p + 3].toInt() and 0xFF
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    /**
     * 8-bit indexed, with transparency from the AND mask.
     *
     * The palette carries no usable alpha - it is almost always zero - so the mask below the
     * pixels is the only thing that says which pixels are the icon. Ignoring it would paint
     * every one of these as an opaque rectangle.
     */
    private fun readPalettised(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        width: Int,
        height: Int,
    ): IntArray? {
        val declared = bytes.u32(offset + 32)
        val colours = if (declared in 1..256) declared else 256
        val palette = offset + headerSize
        val data = palette + colours * 4
        // Rows are padded to four bytes, in both the pixels and the mask.
        val stride = (width + 3) / 4 * 4
        val maskStride = (width + 31) / 32 * 4
        val mask = data + stride * height
        if (mask + maskStride * height > bytes.size) return null

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val src = (height - 1 - y)
            for (x in 0 until width) {
                val index = bytes[data + src * stride + x].toInt() and 0xFF
                val entry = palette + index * 4
                val b = bytes[entry].toInt() and 0xFF
                val g = bytes[entry + 1].toInt() and 0xFF
                val r = bytes[entry + 2].toInt() and 0xFF
                // A set bit in the mask means transparent, which is the opposite of intuition.
                val bit = bytes[mask + src * maskStride + x / 8].toInt() shr (7 - x % 8) and 1
                val a = if (bit == 1) 0 else 255
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    private fun ByteArray.isPngAt(at: Int) = at + 8 <= size &&
        this[at] == 0x89.toByte() && this[at + 1] == 'P'.code.toByte() &&
        this[at + 2] == 'N'.code.toByte() && this[at + 3] == 'G'.code.toByte()

    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(at: Int) = (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0 Mobile Safari/537.36"
}

/** Null until it arrives, and null forever if the site has nothing this device can draw. */
@Composable
fun rememberFavicon(host: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, key1 = host) {
        value = Favicons.of(host)
    }.value
