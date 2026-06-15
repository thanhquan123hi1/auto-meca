package com.mecanum.autocar.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmapRotated(): Bitmap {
    val nv21 = yuv420ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val output = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 82, output)
    val bytes = output.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bitmap.recycle()
    return rotated
}

private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val ySize = image.width * image.height
    val uvSize = image.width * image.height / 2
    val output = ByteArray(ySize + uvSize)

    copyPlane(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, image.width, image.height, output, 0, 1)
    val chromaWidth = image.width / 2
    val chromaHeight = image.height / 2
    copyInterleavedChroma(vPlane, uPlane, chromaWidth, chromaHeight, output, ySize)
    return output
}

private fun copyPlane(
    buffer: java.nio.ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    output: ByteArray,
    outputOffset: Int,
    outputPixelStride: Int,
) {
    var outputIndex = outputOffset
    val row = ByteArray(rowStride)
    buffer.rewind()
    for (y in 0 until height) {
        val length = if (pixelStride == 1) width else (width - 1) * pixelStride + 1
        buffer.get(row, 0, length)
        for (x in 0 until width) {
            output[outputIndex] = row[x * pixelStride]
            outputIndex += outputPixelStride
        }
        if (y < height - 1) {
            buffer.position(buffer.position() + rowStride - length)
        }
    }
}

private fun copyInterleavedChroma(
    vPlane: ImageProxy.PlaneProxy,
    uPlane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    output: ByteArray,
    offset: Int,
) {
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRow = ByteArray(uPlane.rowStride)
    val vRow = ByteArray(vPlane.rowStride)
    var outputIndex = offset
    uBuffer.rewind()
    vBuffer.rewind()

    for (y in 0 until height) {
        val uLength = if (uPlane.pixelStride == 1) width else (width - 1) * uPlane.pixelStride + 1
        val vLength = if (vPlane.pixelStride == 1) width else (width - 1) * vPlane.pixelStride + 1
        uBuffer.get(uRow, 0, uLength)
        vBuffer.get(vRow, 0, vLength)
        for (x in 0 until width) {
            output[outputIndex++] = vRow[x * vPlane.pixelStride]
            output[outputIndex++] = uRow[x * uPlane.pixelStride]
        }
        if (y < height - 1) {
            uBuffer.position(uBuffer.position() + uPlane.rowStride - uLength)
            vBuffer.position(vBuffer.position() + vPlane.rowStride - vLength)
        }
    }
}
