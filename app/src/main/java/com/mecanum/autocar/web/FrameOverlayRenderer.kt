package com.mecanum.autocar.web

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mecanum.autocar.ai.VisionResult

/**
 * Vẽ khung nhận diện (vật cản, marker) trực tiếp lên một bản sao bitmap kích thước ảnh gốc.
 * Dùng cho luồng video web khi bật overlay. Toạ độ là toạ độ ảnh nên không cần biến đổi.
 */
object FrameOverlayRenderer {
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
        textSize = 22f
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * Trả về một bitmap MỚI (mutable) đã vẽ overlay. Người gọi chịu trách nhiệm recycle.
     */
    fun render(source: Bitmap, result: VisionResult?): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        if (result == null) return copy
        val canvas = Canvas(copy)

        for (detection in result.detections) {
            val box = detection.box
            canvas.drawRect(box, obstaclePaint)
            val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            drawLabel(canvas, label, box.left, (box.top - 6f).coerceAtLeast(20f))
        }

        result.marker?.let { marker ->
            canvas.drawRect(marker.box, markerPaint)
            canvas.drawCircle(marker.centerX, marker.centerY, 6f, markerPaint)
            val label = buildString {
                append("ArUco ${marker.id}")
                marker.distanceCm?.let { append("  ${"%.0f".format(it)}cm") }
            }
            drawLabel(canvas, label, marker.box.left, (marker.box.bottom + 22f).coerceAtMost(copy.height - 4f))
        }
        return copy
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val width = textPaint.measureText(text)
        canvas.drawRect(x - 2f, y - 20f, x + width + 4f, y + 6f, textBgPaint)
        canvas.drawText(text, x, y, textPaint)
    }
}