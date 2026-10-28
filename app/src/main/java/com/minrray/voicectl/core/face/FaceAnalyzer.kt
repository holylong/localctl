package com.minrray.voicectl.core.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker.FaceLandmarkerOptions
import kotlin.math.*

private const val TAG = "FaceAnalyzer"

class FaceAnalyzer(private val context: Context) {

    companion object {
        private const val HISTORY_LENGTH = 15
        private const val SPEAK_THRESHOLD = 0.020f
        private const val SPEAK_MIN_DURATION = 0.25f
        private const val DEFAULT_DIRECTION_THRESHOLD = 30.0f
        private const val QUIET_FRAME_THRESHOLD = 3

        // 嘴唇关键点索引
        private const val UPPER_LIP_CENTER = 13
        private const val LOWER_LIP_CENTER = 14
        private const val LEFT_CORNER = 61
        private const val RIGHT_CORNER = 291

        // 人脸关键点索引
        private const val NOSE_TIP = 1
        private const val LEFT_EYE = 33
        private const val RIGHT_EYE = 263
        private const val CHIN = 152
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var isInitialized = false

    // 唇动检测历史数据
    private val marHistory = ArrayDeque<Float>(HISTORY_LENGTH)
    private val timestampHistory = ArrayDeque<Long>(HISTORY_LENGTH)

    // 说话状态跟踪
    private var isSpeaking = false
    private var speakStartTime: Long = 0
    private var quietFrameCount = 0  // 连续安静帧计数

    // 人脸方向检测计数器
    private var directionDetectCount = 0

    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "FaceAnalyzer already initialized")
            return true
        }

        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            isInitialized = true
            Log.d(TAG, "FaceAnalyzer initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceAnalyzer", e)
            e.printStackTrace()
            false
        }
    }

    fun analyze(
        bitmap: Bitmap,
        faceRect: Rect? = null,
        directionThreshold: Float = DEFAULT_DIRECTION_THRESHOLD
    ): FaceAnalysisResult {
        if (!isInitialized) {
            Log.e(TAG, "FaceAnalyzer not initialized")
            return FaceAnalysisResult(
                faceDetected = false,
                faceDirectionResult = FaceDirectionResult(-1, 0f, 0f, 0f, 0f, null),
                lipMovementResult = LipMovementResult(false, 0f, false, "unknown", 0f, false, false)
            )
        }

        val landmarker = faceLandmarker ?: return FaceAnalysisResult(
            faceDetected = false,
            faceDirectionResult = FaceDirectionResult(-1, 0f, 0f, 0f, 0f, null),
            lipMovementResult = LipMovementResult(false, 0f, false, "unknown", 0f, false, false)
        )

        return try {
            val faceBitmap = if (faceRect != null) {
                val expandedRect = expandFaceRect(faceRect, bitmap.width, bitmap.height)
                Bitmap.createBitmap(
                    bitmap,
                    expandedRect.left,
                    expandedRect.top,
                    expandedRect.width(),
                    expandedRect.height()
                )
            } else {
                bitmap
            }

            val faceBitmapWidth = faceBitmap.width
            val faceBitmapHeight = faceBitmap.height

            val mpImage = BitmapImageBuilder(faceBitmap).build()
            val result = landmarker.detect(mpImage)

            if (faceBitmap != bitmap) {
                faceBitmap.recycle()
            }

            if (result.faceLandmarks().isEmpty()) {
                return FaceAnalysisResult(
                    faceDetected = false,
                    faceDirectionResult = FaceDirectionResult(-1, 0f, 0f, 0f, 0f, null),
                    lipMovementResult = LipMovementResult(false, 0f, false, "unknown", 0f, false, false)
                )
            }

            val landmarks = result.faceLandmarks()[0]
            val currentTime = System.currentTimeMillis()

            // 计算人脸方向
            val headPose = calculateHeadPoseFromLandmarks(landmarks, faceBitmapWidth, faceBitmapHeight)
            val tIsFacing = abs(headPose.yaw) < directionThreshold

            directionDetectCount++
            if (directionDetectCount % 50 == 0) {
                Log.d(TAG, "人脸角度检测结果: 水平角度(Yaw)=${String.format("%.2f", headPose.yaw)}°, 俯仰角度(Pitch)=${String.format("%.2f", headPose.pitch)}°, 翻滚角度(Roll)=${String.format("%.2f", headPose.roll)}°, 视线偏离角度=${String.format("%.2f", abs(headPose.yaw))}°, 是否正对摄像头=${if (tIsFacing) "是" else "否"}")
            }

            val faceDirectionResult = FaceDirectionResult(
                isFacing = if (tIsFacing) 1 else 0,
                gazeAngle = abs(headPose.yaw),
                pitch = headPose.pitch,
                yaw = headPose.yaw,
                roll = headPose.roll,
                facePose = headPose
            )

            // 计算唇动
            val currentMar = calculateMouthAspectRatio(landmarks, faceBitmapWidth, faceBitmapHeight)

            marHistory.add(currentMar)
            timestampHistory.add(currentTime)

            val pattern = analyzeLipMovementPattern()
            val isSpeakingNow = detectSpeech(currentMar, currentTime)
            val lipMovementDetected = detectLipMovementChange(currentMar)

           // Log.d(TAG, "Lip movement detected: isSpeaking=$isSpeakingNow, lipMovementDetected=$lipMovementDetected, mar=$currentMar")

            val lipMovementResult = LipMovementResult(
                faceDetected = true,
                mar = currentMar,
                isSpeaking = isSpeakingNow,
                trend = pattern.trend,
                velocity = pattern.velocity,
                isRhythmic = pattern.isRhythmic,
                lipMovementDetected = lipMovementDetected
            )

            FaceAnalysisResult(
                faceDetected = true,
                faceDirectionResult = faceDirectionResult,
                lipMovementResult = lipMovementResult
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing face", e)
            e.printStackTrace()
            FaceAnalysisResult(
                faceDetected = false,
                faceDirectionResult = FaceDirectionResult(-1, 0f, 0f, 0f, 0f, null),
                lipMovementResult = LipMovementResult(false, 0f, false, "unknown", 0f, false, false)
            )
        }
    }

    private fun calculateMouthAspectRatio(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val upperLip = landmarks[UPPER_LIP_CENTER]
        val lowerLip = landmarks[LOWER_LIP_CENTER]
        val leftCorner = landmarks[LEFT_CORNER]
        val rightCorner = landmarks[RIGHT_CORNER]

        val upperLipX = upperLip.x() * imageWidth
        val upperLipY = upperLip.y() * imageHeight
        val lowerLipX = lowerLip.x() * imageWidth
        val lowerLipY = lowerLip.y() * imageHeight
        val leftCornerX = leftCorner.x() * imageWidth
        val leftCornerY = leftCorner.y() * imageHeight
        val rightCornerX = rightCorner.x() * imageWidth
        val rightCornerY = rightCorner.y() * imageHeight

        val verticalDist = sqrt(
            (lowerLipX - upperLipX).pow(2) + (lowerLipY - upperLipY).pow(2)
        )
        val horizontalWidth = sqrt(
            (rightCornerX - leftCornerX).pow(2) + (rightCornerY - leftCornerY).pow(2)
        )

        return if (horizontalWidth > 0) (verticalDist / horizontalWidth).toFloat() else 0f
    }

    private fun calculateHeadPoseFromLandmarks(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): HeadPose {
        val noseTip = landmarks[NOSE_TIP]
        val leftEye = landmarks[LEFT_EYE]
        val rightEye = landmarks[RIGHT_EYE]
        val chin = landmarks[CHIN]

        // 转换为像素坐标
        val noseTipX = noseTip.x() * imageWidth
        val noseTipY = noseTip.y() * imageHeight
        val leftEyeX = leftEye.x() * imageWidth
        val leftEyeY = leftEye.y() * imageHeight
        val rightEyeX = rightEye.x() * imageWidth
        val rightEyeY = rightEye.y() * imageHeight
        val chinX = chin.x() * imageWidth
        val chinY = chin.y() * imageHeight

        // 1. 计算Yaw（左右转头） - 现有的方法基本正确
        val centerX = (leftEyeX + rightEyeX) / 2.0
        val centerY = (leftEyeY + rightEyeY) / 2.0
        val faceWidth = rightEyeX - leftEyeX
        val noseOffsetX = noseTipX - centerX

        // 使用更准确的三角函数计算yaw
        val yaw = if (abs(faceWidth) > 0) {
            Math.toDegrees(asin(noseOffsetX / (faceWidth + 0.001))).toFloat()
        } else {
            0f
        }

        // 2. 计算Pitch（抬头低头） - 修正的方法
        // 使用下巴到眼睛中心的垂直距离作为参考
        val faceHeight = chinY - centerY
        val noseOffsetY = noseTipY - centerY

        // 使用反正切函数计算pitch，使用faceHeight作为参考
        val pitch = if (faceHeight > 0) {
            -Math.toDegrees(atan2(noseOffsetY.toDouble(), faceHeight.toDouble())).toFloat()
        } else {
            0f
        }

        // 3. 计算Roll（侧头） - 现有的方法基本正确
        val eyeAngle = atan2(
            (rightEyeY - leftEyeY).toDouble(),
            (rightEyeX - leftEyeX).toDouble()
        )
        val roll = Math.toDegrees(eyeAngle).toFloat()

        return HeadPose(pitch, yaw, roll)
    }

    private fun analyzeLipMovementPattern(): LipMovementPattern {
        if (marHistory.size < 3) {
            return LipMovementPattern("unknown", 0f, 0f, false)
        }

        val recentMars = marHistory.toList()
        val velocities = mutableListOf<Float>()

        for (i in 1 until recentMars.size) {
            val dt = (timestampHistory[i] - timestampHistory[i - 1]) / 1000.0f
            if (dt > 0) {
                val velocity = abs(recentMars[i] - recentMars[i - 1]) / dt
                velocities.add(velocity)
            }
        }

        val avgVelocity = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f

        val avgAcceleration = if (velocities.size >= 2) {
            val accelerations = mutableListOf<Float>()
            for (i in 1 until velocities.size) {
                accelerations.add(velocities[i] - velocities[i - 1])
            }
            accelerations.average().toFloat()
        } else {
            0f
        }

        val trend = if (recentMars.size >= 3) {
            val recentChange = recentMars.last() - recentMars[recentMars.size - 3]
            when {
                recentChange > 0.01f -> "opening"
                recentChange < -0.01f -> "closing"
                else -> "stable"
            }
        } else {
            "unknown"
        }

        val isRhythmic = if (velocities.size >= 5) {
            val velocityStd = calculateStandardDeviation(velocities)
            val velocityMean = avgVelocity
            if (velocityStd > 0 && velocityMean > 0) {
                val cv = velocityStd / velocityMean
                cv > 0.3f && cv < 1.5f
            } else {
                false
            }
        } else {
            false
        }

        return LipMovementPattern(trend, avgVelocity, avgAcceleration, isRhythmic)
    }

    private fun detectSpeech(currentMar: Float, currentTime: Long): Boolean {
        if (marHistory.size < 2) {
            return false
        }

        val recentMarHistory = marHistory.takeLast(3)
        val marStdDev = calculateStandardDeviation(recentMarHistory.toList())
        
       // Log.d(TAG, "detectSpeech: marStdDev=$marStdDev, SPEAK_THRESHOLD=$SPEAK_THRESHOLD, marHistory.size=${marHistory.size}, recentMarHistory.size=${recentMarHistory.size}, isSpeaking=$isSpeaking, quietFrameCount=$quietFrameCount")

        val isLipMoving = marStdDev > SPEAK_THRESHOLD

        if (isLipMoving) {
            if (!isSpeaking) {
                isSpeaking = true
                speakStartTime = currentTime
                quietFrameCount = 0
                Log.d(TAG, "detectSpeech: start speaking, stdDev=$marStdDev")
            } else {
                quietFrameCount = 0
            }
            return true
        } else {
            if (isSpeaking) {
                quietFrameCount++
                val duration = (currentTime - speakStartTime) / 1000.0f
                
                //Log.d(TAG, "detectSpeech: was speaking, duration=$duration, quietFrameCount=$quietFrameCount, recentStdDev=$marStdDev")
                
                if (duration >= SPEAK_MIN_DURATION && quietFrameCount >= QUIET_FRAME_THRESHOLD) {
                    isSpeaking = false
                    speakStartTime = 0
                    quietFrameCount = 0
                   // Log.d(TAG, "detectSpeech: stop speaking after $quietFrameCount quiet frames")
                    return false
                }
                return true
            }
            return false
        }
    }

    private fun detectLipMovementChange(currentMar: Float): Boolean {
        if (marHistory.size < 2) {
            return false
        }
        val marChange = abs(currentMar - marHistory[marHistory.size - 2])
        return marChange > SPEAK_THRESHOLD
    }

    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        var sumSquaredDiff = 0.0
        for (value in values) {
            sumSquaredDiff += (value - mean).toDouble().pow(2)
        }
        return (sumSquaredDiff / values.size).toFloat()
    }

    private fun calculateStandardDeviation(values: List<Float>): Float {
        return sqrt(calculateVariance(values).toDouble()).toFloat()
    }

    private fun expandFaceRect(rect: Rect, imageWidth: Int, imageHeight: Int, expandRatio: Float = 0.3f): Rect {
        val expandWidth = (rect.width() * expandRatio).toInt()
        val expandHeight = (rect.height() * expandRatio).toInt()

        val left = maxOf(0, rect.left - expandWidth)
        val top = maxOf(0, rect.top - expandHeight)
        val right = minOf(imageWidth, rect.right + expandWidth)
        val bottom = minOf(imageHeight, rect.bottom + expandHeight)

        return Rect(left, top, right, bottom)
    }

    fun release() {
        faceLandmarker?.close()
        faceLandmarker = null
        isInitialized = false
        marHistory.clear()
        timestampHistory.clear()
        Log.d(TAG, "FaceAnalyzer released")
    }

    data class HeadPose(
        val pitch: Float,
        val yaw: Float,
        val roll: Float
    )

    data class FaceDirectionResult(
        val isFacing: Int,
        val gazeAngle: Float,
        val pitch: Float,
        val yaw: Float,
        val roll: Float,
        val facePose: HeadPose?
    )

    data class LipMovementPattern(
        val trend: String,
        val velocity: Float,
        val acceleration: Float,
        val isRhythmic: Boolean
    )

    data class LipMovementResult(
        val faceDetected: Boolean,
        val mar: Float,
        val isSpeaking: Boolean,
        val trend: String,
        val velocity: Float,
        val isRhythmic: Boolean,
        val lipMovementDetected: Boolean
    )

    data class FaceAnalysisResult(
        val faceDetected: Boolean,
        val faceDirectionResult: FaceDirectionResult,
        val lipMovementResult: LipMovementResult
    )
}
