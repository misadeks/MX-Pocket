package tel.misa.mxpocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.ViewGroup
import tel.misa.mxpocket.R
import tel.misa.mxpocket.driver.KeypadProtocol
import kotlin.math.roundToInt

/** Live phone mirror. Keep gaps as well as keys in the same coordinate system as the LCD. */
internal class KeypadPanel(context: Context) : ViewGroup(context) {
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    private var art: Bitmap? = null
    private var images = emptyList<Bitmap>()
    private val minKeyHeight = resources.getDimensionPixelSize(R.dimen.screen_key_min_height)
    init { setWillNotDraw(false) }

    fun show(art: Bitmap?, images: List<Bitmap>) {
        if (this.art === art && this.images == images) return
        this.art = art
        this.images = images
        invalidate()
    }
    private fun region(i: Int, side: Int) = KeypadProtocol.keyRegion(i).let {
        fun px(v: Int) = (v * side / 480f).roundToInt()
        Rect(px(it.x), px(it.y), px(it.x + it.width), px(it.y + it.height))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(side, maxOf(side, 3 * minKeyHeight))
        for (i in 0 until childCount) {
            val r = bounds(i, side, measuredHeight)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        }
    }

    private fun bounds(i: Int, side: Int, height: Int): Rect {
        val row = i / 3
        val r = region(i, side)
        // The painted square matches the LCD; use the surrounding gap for the native touch target.
        val h = maxOf(r.height(), minKeyHeight)
        val y = (r.centerY() + (height - side) / 2 - h / 2)
            .coerceIn(row * minKeyHeight, height - (2 - row) * minKeyHeight - h)
        return Rect(r.left, y, r.right, y + h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) bounds(i, width, height).let { getChildAt(i).layout(it.left, it.top, it.right, it.bottom) }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(0f, (height - width) / 2f)
        val panel = Rect(0,0,width,width)
        canvas.drawRect(panel, filter)
        art?.let { canvas.drawBitmap(it, null, panel, filter) }
        images.forEachIndexed { i, tile -> canvas.drawBitmap(tile, null, region(i, width), filter) }
        canvas.restore()
    }
}
