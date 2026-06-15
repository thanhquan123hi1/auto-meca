package com.mecanum.autocar.ai

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import kotlin.math.abs

class ArucoGoalDetector(
    private val targetId: Int = 0,
    private val minArea: Float = 80f,
) : AutoCloseable {
    private val detector: ArucoDetector

    init {
        OpenCVLoader.initDebug()
        val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_1000)
        val parameters = DetectorParameters()
        parameters.set_adaptiveThreshWinSizeMin(3)
        parameters.set_adaptiveThreshWinSizeMax(53)
        parameters.set_adaptiveThreshWinSizeStep(4)
        parameters.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        detector = ArucoDetector(dictionary, parameters)
    }

    fun detect(bitmap: Bitmap): GoalMarker? {
        val rgba = Mat()
        val gray = Mat()
        val ids = Mat()
        val corners = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            detector.detectMarkers(gray, corners, ids)
            if (ids.empty()) return null

            var best: GoalMarker? = null
            for (i in 0 until ids.rows()) {
                val id = ids.get(i, 0)?.firstOrNull()?.toInt() ?: continue
                if (id != targetId) continue
                val points = readPoints(corners.getOrNull(i) ?: continue)
                if (points.size != 4) continue
                val marker = toMarker(id, points)
                if (marker.area < minArea) continue
                if (best == null || marker.area > best.area) best = marker
            }
            return best
        } finally {
            rgba.release()
            gray.release()
            ids.release()
            corners.forEach { it.release() }
        }
    }

    private fun readPoints(mat: Mat): List<Point> {
        val points = ArrayList<Point>(4)
        for (i in 0 until 4) {
            val value = mat.get(0, i) ?: continue
            if (value.size >= 2) points.add(Point(value[0], value[1]))
        }
        return points
    }

    private fun toMarker(id: Int, points: List<Point>): GoalMarker {
        val left = points.minOf { it.x }.toFloat()
        val top = points.minOf { it.y }.toFloat()
        val right = points.maxOf { it.x }.toFloat()
        val bottom = points.maxOf { it.y }.toFloat()
        val centerX = points.map { it.x }.average().toFloat()
        val centerY = points.map { it.y }.average().toFloat()
        val area = polygonArea(points).toFloat()
        return GoalMarker(id, RectF(left, top, right, bottom), centerX, centerY, area)
    }

    private fun polygonArea(points: List<Point>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    override fun close() = Unit
}
