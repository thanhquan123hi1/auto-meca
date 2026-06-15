package com.mecanum.autocar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.mecanum.autocar.ai.VisionResult
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 112, 77)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(72, 220, 160)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        strokeWidth = 2f
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    @Volatile private var result: VisionResult? = null

    fun update(next: VisionResult?) {
        result = next
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = result ?: return
        if (current.frameWidth <= 0 || current.frameHeight <= 0) return

        val transform = Transform(
            imageWidth = current.frameWidth.toFloat(),
            imageHeight = current.frameHeight.toFloat(),
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
        )

        val third = current.frameWidth / 3f
        canvas.drawLine(transform.x(third), 0f, transform.x(third), height.toFloat(), guidePaint)
        canvas.drawLine(transform.x(third * 2f), 0f, transform.x(third * 2f), height.toFloat(), guidePaint)

        for (detection in current.detections) {
            val box = transform.rect(detection.box)
            canvas.drawRect(box, obstaclePaint)
            canvas.drawText(
                "${detection.label} ${(detection.confidence * 100).toInt()}%",
                box.left,
                (box.top - 8f).coerceAtLeast(28f),
                textPaint,
            )
        }

        current.marker?.let { marker ->
            val box = transform.rect(marker.box)
            canvas.drawRect(box, markerPaint)
            canvas.drawCircle(transform.x(marker.centerX), transform.y(marker.centerY), 8f, markerPaint)
            canvas.drawText("ArUco ${marker.id}", box.left, (box.bottom + 30f).coerceAtMost(height - 8f), textPaint)
        }
    }

    private class Transform(
        imageWidth: Float,
        imageHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
    ) {
        private val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        private val dx = (viewWidth - imageWidth * scale) / 2f
        private val dy = (viewHeight - imageHeight * scale) / 2f

        fun x(value: Float): Float = value * scale + dx
        fun y(value: Float): Float = value * scale + dy
        fun rect(rect: RectF): RectF = RectF(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom))
    }
}
