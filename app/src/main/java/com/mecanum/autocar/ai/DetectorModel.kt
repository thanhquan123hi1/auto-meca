package com.mecanum.autocar.ai

data class DetectorModel(
    val id: String,
    val displayName: String,
    val assetName: String,
) {
    companion object {
        val OPTIONS = listOf(
            DetectorModel("yolo26n", "YOLO26n", "yolo26n.tflite"),
            DetectorModel("yolo11n", "YOLO11n", "yolo11n.tflite"),
            DetectorModel("yolov8n", "YOLOv8n", "yolov8n.tflite"),
            DetectorModel("yolo26s", "YOLO26s", "yolo26s.tflite"),
        )

        fun byId(id: String): DetectorModel =
            OPTIONS.firstOrNull { it.id == id } ?: OPTIONS.first()
    }
}
