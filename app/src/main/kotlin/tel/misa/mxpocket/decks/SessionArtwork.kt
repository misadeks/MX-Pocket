package tel.misa.mxpocket.decks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.net.Uri
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.IOException
import tel.misa.mxpocket.driver.KeypadProtocol

/** Sources come only from the selected session's metadata; never follow a network or file URL. */
internal class SessionArtwork private constructor(private val bitmap: Bitmap?, private val uri: Uri?) {
    private val generation = bitmap?.generationId
    val isUri get() = uri != null
    fun sameSource(other: SessionArtwork?) = other != null && bitmap === other.bitmap &&
        generation == other.generation && uri == other.uri
    fun readable(context: Context) = uri == null || context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun load(context: Context): Bitmap? {
        if (!readable(context)) return null
        val source = bitmap ?: decode(context) ?: return null
        if (source.isRecycled) return null
        // A software canvas cannot draw a hardware bitmap. Bound the sole full-size readback.
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            if (source.width.toLong() * source.height > 4L * 1024 * 1024) return null
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else source
        val side = minOf(software.width, software.height)
        val x = (software.width - side) / 2
        val y = (software.height - side) / 2
        val crop = Rect(x, y, x + side, y + side)
        return Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.BLACK)
            Canvas(it).drawBitmap(software, crop, coverBounds,
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun decode(context: Context): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri ?: return null)?.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0 || bytes.size() + count > 4 * 1024 * 1024) return null
                    bytes.write(buffer, 0, count)
                }
                val data = bytes.toByteArray()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                if (opts.outWidth !in 1..16384 || opts.outHeight !in 1..16384) return null
                opts.inJustDecodeBounds = false
                opts.inSampleSize = 1
                while (maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 960) opts.inSampleSize *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            }
        } catch (_: IOException) { null } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null }
    }

    companion object {
        // Leave three pixels of bezel clearance around the entire visible grid, not around each key.
        private val coverBounds = KeypadProtocol.keyRegion(0).let { first ->
            val last = KeypadProtocol.keyRegion(8)
            Rect(first.x + 3, first.y + 3, last.x + last.width - 3, last.y + last.height - 3)
        }
        fun from(metadata: MediaMetadata?): SessionArtwork? {
            metadata ?: return null
            for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON))
                metadata.getBitmap(key)?.takeUnless { it.isRecycled }?.let { return SessionArtwork(it, null) }
            for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
                val uri = metadata.getString(key)?.let(Uri::parse) ?: continue
                if (uri.scheme == "content" && !uri.authority.isNullOrBlank()) return SessionArtwork(null, uri)
            }
            return null
        }
    }
}
