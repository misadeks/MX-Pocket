package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import java.io.Closeable

/** One connection, nine LCD keys (0..8). Don't mutate or recycle the caller-owned bitmap during upload. */
interface KeypadSink : Closeable {
    suspend fun setKeyImage(index: Int, bitmap: Bitmap)
}
