package dev.radi8.shinyrock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class PlaydateScreenView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var screenBitmap: Bitmap? = null
    private val paint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val srcRect = Rect()
    private val destRect = Rect()

    companion object {
        const val PD_WIDTH = 400
        const val PD_HEIGHT = 240
    }

    init {
        screenBitmap = Bitmap.createBitmap(PD_WIDTH, PD_HEIGHT, Bitmap.Config.ARGB_8888)
        screenBitmap?.eraseColor(Color.rgb(177, 175, 168))
    }

    fun updateScreenBitmap(newBitmap: Bitmap?) {
        screenBitmap = newBitmap
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (width * (PD_HEIGHT.toFloat() / PD_WIDTH.toFloat())).toInt()
        setMeasuredDimension(width, desiredHeight)
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.rgb(177, 175, 168))

        screenBitmap?.let { bmp ->
            srcRect.set(0, 0, bmp.width, bmp.height)

            val viewWidth = width
            val viewHeight = height
            val bitmapRatio = bmp.width.toFloat() / bmp.height.toFloat()
            val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

            var drawWidth = viewWidth
            var drawHeight = viewHeight
            var xOffset = 0
            var yOffset = 0

            if (viewRatio > bitmapRatio) {
                drawWidth = (viewHeight * bitmapRatio).toInt()
                xOffset = (viewWidth - drawWidth) / 2
            } else {
                drawHeight = (viewWidth / bitmapRatio).toInt()
                yOffset = (viewHeight - drawHeight) / 2
            }

            destRect.set(xOffset, yOffset, xOffset + drawWidth, yOffset + drawHeight)

            canvas.drawBitmap(bmp, srcRect, destRect, paint)
        } ?: run {
            val placeholderPaint = Paint().apply {
                color = Color.WHITE
                textSize = 40f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No Signal", width / 2f, height / 2f, placeholderPaint)
        }
    }
}