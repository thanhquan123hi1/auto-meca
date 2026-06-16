package com.mecanum.autocar.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloTfliteDetector(
    context: Context,
    modelAssetName: String = "yolo26n.tflite",
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.45f,
    private val maxDetections: Int = 20,
) : AutoCloseable {
    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputType: DataType

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
            setUseNNAPI(true)
        }
        interpreter = Interpreter(loadModel(context, modelAssetName), options)

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        inputType = inputTensor.dataType()
    }

    data class DetectionMetrics(val detections: List<Detection>, val inferenceMs: Long, val preprocessMs: Long)

    fun detect(bitmap: Bitmap): DetectionMetrics {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val input = bitmapToInput(scaled)
        val preprocessMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
        val outputShape = interpreter.getOutputTensor(0).shape()

        val output = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        val inferenceStartedAt = SystemClock.elapsedRealtimeNanos()
        interpreter.run(input, output)

        val detections = parseOutput(output[0], outputShape, bitmap.width, bitmap.height)
        val inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartedAt) / 1_000_000
        if (scaled !== bitmap) scaled.recycle()
        return DetectionMetrics(detections = detections, inferenceMs = inferenceMs, preprocessMs = preprocessMs)
    }

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (inputType == DataType.FLOAT32) {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            } else {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun parseOutput(
        raw: Array<FloatArray>,
        shape: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<Detection> {
        if (shape.size != 3) return emptyList()
        val dimA = shape[1]
        val dimB = shape[2]
        val boxesAreRows = dimB >= 6
        val boxCount = if (boxesAreRows) dimA else dimB
        val attrCount = if (boxesAreRows) dimB else dimA
        if (attrCount < 5) return emptyList()

        val candidates = ArrayList<Detection>()
        for (i in 0 until boxCount) {
            val attrs = FloatArray(attrCount)
            for (j in 0 until attrCount) {
                attrs[j] = if (boxesAreRows) raw[i][j] else raw[j][i]
            }

            if (attrCount == 6) {
                val detection = parseNmsRow(attrs, sourceWidth, sourceHeight)
                if (detection != null) candidates.add(detection)
                continue
            }

            val classStart = if (attrCount == 84 || attrCount == 85) 4 else if (attrCount > 5) 5 else 4
            val objectness = if (classStart == 5) attrs[4].coerceIn(0f, 1f) else 1f
            var bestClass = 0
            var bestClassScore = 0f
            for (c in classStart until attrCount) {
                if (attrs[c] > bestClassScore) {
                    bestClassScore = attrs[c]
                    bestClass = c - classStart
                }
            }
            val score = objectness * bestClassScore
            if (score < confidenceThreshold) continue

            val cx = attrs[0]
            val cy = attrs[1]
            val w = attrs[2]
            val h = attrs[3]
            val normalized = cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f
            val scaleX = if (normalized) sourceWidth.toFloat() else sourceWidth.toFloat() / inputWidth
            val scaleY = if (normalized) sourceHeight.toFloat() else sourceHeight.toFloat() / inputHeight

            val left = (cx - w / 2f) * scaleX
            val top = (cy - h / 2f) * scaleY
            val right = (cx + w / 2f) * scaleX
            val bottom = (cy + h / 2f) * scaleY
            val box = RectF(
                left.coerceIn(0f, sourceWidth.toFloat()),
                top.coerceIn(0f, sourceHeight.toFloat()),
                right.coerceIn(0f, sourceWidth.toFloat()),
                bottom.coerceIn(0f, sourceHeight.toFloat()),
            )
            if (box.width() <= 1f || box.height() <= 1f) continue
            candidates.add(Detection("class_$bestClass", score, box))
        }

        return nonMaxSuppression(candidates)
    }

    private fun parseNmsRow(
        attrs: FloatArray,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Detection? {
        val confidence = attrs[4]
        if (confidence < confidenceThreshold || confidence.isNaN()) return null

        val maxCoord = max(max(attrs[0], attrs[1]), max(attrs[2], attrs[3]))
        val normalized = maxCoord <= 1.5f
        val scaleX = if (normalized) sourceWidth.toFloat() else sourceWidth.toFloat() / inputWidth
        val scaleY = if (normalized) sourceHeight.toFloat() else sourceHeight.toFloat() / inputHeight

        val left = attrs[0] * scaleX
        val top = attrs[1] * scaleY
        val right = attrs[2] * scaleX
        val bottom = attrs[3] * scaleY
        val box = RectF(
            min(left, right).coerceIn(0f, sourceWidth.toFloat()),
            min(top, bottom).coerceIn(0f, sourceHeight.toFloat()),
            max(left, right).coerceIn(0f, sourceWidth.toFloat()),
            max(top, bottom).coerceIn(0f, sourceHeight.toFloat()),
        )
        if (box.width() <= 1f || box.height() <= 1f) return null

        val classId = attrs[5].toInt()
        return Detection("class_$classId", confidence, box)
    }

    private fun nonMaxSuppression(candidates: List<Detection>): List<Detection> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val selected = ArrayList<Detection>()
        while (sorted.isNotEmpty() && selected.size < maxDetections) {
            val best = sorted.removeAt(0)
            selected.add(best)
            sorted.removeAll { iou(best.box, it.box) >= iouThreshold }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        interpreter.close()
    }

    private fun loadModel(context: Context, assetName: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(assetName)
        FileInputStream(descriptor.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength,
            )
        }
    }
}
