package tel.misa.mxpocket.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import tel.misa.mxpocket.plugins.IntegrationApp

/** Start's registered application identities, not process names or executable locations. */
internal fun windowsIntegrationIcons(): Map<IntegrationApp, ImageBitmap> {
    if (!Platform.isWindows()) return emptyMap()
    return try { readStartIcons() } catch (_: LinkageError) {
        // A missing native Shell/JNA entry point must not prevent the workspace opening.
        emptyMap()
    }
}

private fun readStartIcons(): Map<IntegrationApp, ImageBitmap> {
    val result = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).toInt()
    if (result < 0 && result != WinError.RPC_E_CHANGED_MODE) return emptyMap()
    return try {
        buildMap {
            fun load(app: IntegrationApp, vararg ids: String) {
                ids.firstNotNullOfOrNull(::startIcon)?.let { put(app, it) }
            }
            load(IntegrationApp.TEAMS, "MSTeams_8wekyb3d8bbwe!MSTeams", "com.squirrel.Teams.Teams")
            load(IntegrationApp.ONENOTE, "Microsoft.Office.ONENOTE.EXE.15", "Microsoft.Office.OneNote_8wekyb3d8bbwe!microsoft.onenoteim")
        }
    } finally { if (result >= 0) Ole32.INSTANCE.CoUninitialize() }
}

private interface ShellItems : StdCallLibrary {
    fun SHCreateItemFromParsingName(name: WString, context: Pointer?, iid: Guid.REFIID, item: PointerByReference): Int
}

private val shell by lazy { Native.load("shell32", ShellItems::class.java) }
private val imageFactory = Guid.REFIID(Guid.IID("{BCC18B79-BA16-442F-80C4-8A59C30C463B}"))
private const val SIDE = 64

private class IconFactory(pointer: Pointer) : Unknown(pointer) {
    fun bitmap(): WinDef.HBITMAP? {
        val out = PointerByReference()
        // IShellItemImageFactory::GetImage(SIZE by value = two LONGs, SIIGBF_ICONONLY, HBITMAP*).
        val size = (SIDE.toLong() shl 32) or SIDE.toLong()
        val hr = _invokeNativeInt(3, arrayOf(pointer, size, 4, out))
        return if (hr >= 0 && out.value != null) WinDef.HBITMAP(out.value) else null
    }
}

private fun startIcon(id: String): ImageBitmap? {
    val item = PointerByReference()
    if (shell.SHCreateItemFromParsingName(WString("shell:AppsFolder\\$id"), null, imageFactory, item) < 0) return null
    val factory = IconFactory(item.value)
    val bitmap = try { factory.bitmap() } finally { factory.Release() }
    if (bitmap == null) return null
    val gdi = GDI32.INSTANCE
    try {
        val dimensions = WinGDI.BITMAP()
        if (gdi.GetObject(bitmap, dimensions.size(), dimensions.pointer) == 0) return null
        dimensions.read()
        val w = dimensions.bmWidth.toInt()
        val h = dimensions.bmHeight.toInt()
        if (w !in 1..SIDE || h !in 1..SIDE) return null
        val header = WinGDI.BITMAPINFO().apply {
            bmiHeader.biWidth = w; bmiHeader.biHeight = -h
            bmiHeader.biPlanes = 1; bmiHeader.biBitCount = 32
        }
        val dc = User32.INSTANCE.GetDC(null) ?: return null
        try {
            Memory((w * h * 4).toLong()).use { bytes ->
                if (gdi.GetDIBits(dc, bitmap, 0, h, bytes, header, WinGDI.DIB_RGB_COLORS) != h) return null
                val pixels = bytes.getByteArray(0, w * h * 4)
                if ((3 until pixels.size step 4).none { pixels[it] != 0.toByte() }) return null
                val image = Bitmap()
                if (!image.installPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), pixels, w * 4)) {
                    image.close()
                    return null
                }
                return image.setImmutable().asComposeImageBitmap()
            }
        } finally { User32.INSTANCE.ReleaseDC(null, dc) }
    } finally { gdi.DeleteObject(bitmap) }
}
