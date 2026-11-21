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
    private val margin = resources.getDimensionPixelSize(R.dimen.screen_control_margin)
    private val minKeyHeight = resources.getDimensionPixelSize(R.dimen.screen_key_min_height)
    private var rowHeight = 0
    init { setWillNotDraw(false) }

    fun show(art: Bitmap?, images: List<Bitmap>) {
        if (this.art === art && this.images == images) return
        if ((this.art == null) != (art == null)) requestLayout()
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
        if (art == null) {
            // Keep the original wrapping, full-width button grid when there's no cover.
            rowHeight = minKeyHeight
            for (i in 0 until childCount) {
                val w = (side / 3 - 2 * margin).coerceAtLeast(0)
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                rowHeight = maxOf(rowHeight, getChildAt(i).measuredHeight)
            }
        }
        setMeasuredDimension(side, if (art == null) 3 * (rowHeight + 2 * margin) else maxOf(side, 3 * minKeyHeight))
        for (i in 0 until childCount) {
            val r = bounds(i, side, measuredHeight)
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        }
    }

    private fun bounds(i: Int, side: Int, height: Int): Rect {
        val col = i % 3
        val row = i / 3
        if (art == null) {
            val y = margin + row * (rowHeight + 2 * margin)
            return Rect(side * col / 3 + margin, y, maxOf(side * col / 3 + margin, side * (col + 1) / 3 - margin), y + rowHeight)
        }
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
        val image = art ?: return
        canvas.save()
        canvas.translate(0f, (height - width) / 2f)
        canvas.drawBitmap(image, null, Rect(0,0,width,width), filter)
        images.forEachIndexed { i, tile -> canvas.drawBitmap(tile, null, region(i, width), filter) }
        canvas.restore()
    }
}
