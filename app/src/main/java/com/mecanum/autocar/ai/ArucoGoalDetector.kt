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
import kotlin.math.max
import kotlin.math.tan

class ArucoGoalDetector(
    private val targetId: Int = 0,
    private val minArea: Float = 45f,
    private val markerSizeCm: Float = 10f,
    private val horizontalFovDeg: Float = 60f,
) : AutoCloseable {
    private val detector: ArucoDetector

    init {
        OpenCVLoader.initDebug()
        val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_1000)
        val parameters = DetectorParameters()
        parameters.set_adaptiveThreshWinSizeMin(3)
        parameters.set_adaptiveThreshWinSizeMax(63)
        parameters.set_adaptiveThreshWinSizeStep(4)
        parameters.set_minMarkerPerimeterRate(0.015)
        parameters.set_maxMarkerPerimeterRate(5.0)
        parameters.set_polygonalApproxAccuracyRate(0.08)
        parameters.set_perspectiveRemovePixelPerCell(6)
        parameters.set_perspectiveRemoveIgnoredMarginPerCell(0.2)
        parameters.set_errorCorrectionRate(0.75)
        parameters.set_detectInvertedMarker(true)
        parameters.set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
        parameters.set_cornerRefinementWinSize(7)
        parameters.set_cornerRefinementMaxIterations(50)
        detector = ArucoDetector(dictionary, parameters)
    }

    fun detect(bitmap: Bitmap): GoalMarker? {
        val rgba = Mat()
        val gray = Mat()
        val equalized = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            detectInGray(gray, bitmap.width)?.let { return it }

            Imgproc.equalizeHist(gray, equalized)
            return detectInGray(equalized, bitmap.width)
        } finally {
            rgba.release()
            gray.release()
            equalized.release()
        }
    }

    private fun detectInGray(gray: Mat, frameWidth: Int): GoalMarker? {
        val ids = Mat()
        val corners = ArrayList<Mat>()
        try {
            detector.detectMarkers(gray, corners, ids)
            if (ids.empty()) return null
            return selectBestMarker(ids, corners, frameWidth)
        } finally {
            ids.release()
            corners.forEach { it.release() }
        }
    }

    private fun selectBestMarker(ids: Mat, corners: List<Mat>, frameWidth: Int): GoalMarker? {
        var best: GoalMarker? = null
        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)?.firstOrNull()?.toInt() ?: continue
            if (id != targetId) continue
            val points = readPoints(corners.getOrNull(i) ?: continue)
            if (points.size != 4) continue
            val marker = toMarker(id, points, frameWidth)
            if (marker.area < minArea) continue
            if (best == null || marker.area > best.area) best = marker
        }
        return best
    }

    private fun readPoints(mat: Mat): List<Point> {
        val points = ArrayList<Point>(4)
        for (i in 0 until 4) {
            val value = mat.get(0, i) ?: continue
            if (value.size >= 2) points.add(Point(value[0], value[1]))
        }
        return points
    }

    private fun toMarker(id: Int, points: List<Point>, frameWidth: Int): GoalMarker {
        val left = points.minOf { it.x }.toFloat()
        val top = points.minOf { it.y }.toFloat()
        val right = points.maxOf { it.x }.toFloat()
        val bottom = points.maxOf { it.y }.toFloat()
        val centerX = points.map { it.x }.average().toFloat()
        val centerY = points.map { it.y }.average().toFloat()
        val area = polygonArea(points).toFloat()
        val distanceCm = estimateDistanceCm(points, frameWidth)
        return GoalMarker(id, RectF(left, top, right, bottom), centerX, centerY, area, distanceCm)
    }

    private fun estimateDistanceCm(points: List<Point>, frameWidth: Int): Float? {
        if (markerSizeCm <= 0f || horizontalFovDeg <= 1f || frameWidth <= 0) return null

        val edgeLengths = buildList {
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val dx = a.x - b.x
                val dy = a.y - b.y
                add(kotlin.math.sqrt(dx * dx + dy * dy).toFloat())
            }
        }
        val markerWidthPx = edgeLengths.average().toFloat().takeIf { it > 1f } ?: return null
        val focalPx = ((frameWidth / 2f) / tan(Math.toRadians(horizontalFovDeg / 2.0))).toFloat()
        if (focalPx <= 0f) return null
        val distance = (markerSizeCm * focalPx) / max(markerWidthPx, 1f)
        return if (distance.isFinite() && distance > 0f) distance else null
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
