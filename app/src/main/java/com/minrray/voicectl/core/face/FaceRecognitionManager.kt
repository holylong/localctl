package com.minrray.voicectl.core.face

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.hexdeploy.facesdk.FaceDetector
import com.hexdeploy.facesdk.FaceInfo
import com.hexdeploy.facesdk.FaceRecognizer
import com.hexdeploy.facesdk.RecognitionResult
import java.io.ByteArrayOutputStream

interface FaceRecognitionListener {
    fun onFaceDetected(result: RecognitionResult, isFacing: Boolean, lipMovementDetected: Boolean, faceRect: Rect?, faceAngle: Float?)
    fun onFaceNotDetected()
    fun onActiveTrigger(name: String, count: Int)
}

interface FaceRegisterListener {
    fun onFaceRegisterSuccess(name: String)
    fun onFaceRegisterFailed(reason: String)
}

class FaceRecognitionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "FaceRecognitionManager"
        private const val RECOGNITION_PER_SECOND = 10
        private const val RECOGNITION_INTERVAL = 1000L / RECOGNITION_PER_SECOND
        private const val SIMILARITY_THRESHOLD = 0.5f
        private const val REGISTER_SIMILARITY_THRESHOLD = 0.4f
        private const val CAMERA_ID = "0"
        private const val ACTIVE_TRIGGER_WINDOW = 60 * 1000L
        private const val ACTIVE_TRIGGER_COUNT = 3

        @Volatile
        private var instance: FaceRecognitionManager? = null

        fun getInstance(context: Context): FaceRecognitionManager {
            return instance ?: synchronized(this) {
                instance ?: FaceRecognitionManager(context.applicationContext).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.release()
                instance = null
                Log.d(TAG, "FaceRecognitionManager instance reset")
            }
        }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private var previewSurface: Surface? = null
    private var testFGIviewEnabled: Boolean = false

    private var isCameraClosing = false
    private val cameraCloseLock = java.util.concurrent.locks.ReentrantLock()
    private val cameraClosedCondition = cameraCloseLock.newCondition()

    private var faceDetector: FaceDetector? = null
    private var faceRecognizer: FaceRecognizer? = null
    private var faceDBHelper: FaceDBHelper? = null
    private var faceAnalyzer: FaceAnalyzer? = null

    private var isRecognizing = false
    private var lastRecognitionTime = 0L
    private var listener: FaceRecognitionListener? = null

    private var isRegisterMode = false
    private var registerName: String? = null
    private var registerRole: Int = 1
    private var registerListener: FaceRegisterListener? = null

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    data class FaceRecognitionRecord(
        val name: String,
        val timestamps: MutableList<Long> = mutableListOf()
    )

    private val faceRecognitionRecords = mutableMapOf<String, FaceRecognitionRecord>()

    fun initialize() {
        if (faceDetector != null && faceRecognizer != null) {
            Log.d(TAG, "Face recognition already initialized")
            return
        }

        try {
            faceDBHelper = FaceDBHelper(context)
            
            faceDetector = FaceDetector()
            faceRecognizer = FaceRecognizer()

            faceDetector?.init(context)
            faceRecognizer?.init(context)

            faceRecognizer?.setThreshold(SIMILARITY_THRESHOLD)

            loadFaceDatabase()

            faceAnalyzer = FaceAnalyzer(context)
            val analyzerInitialized = faceAnalyzer?.initialize() ?: false
            if (analyzerInitialized) {
                Log.d(TAG, "FaceAnalyzer initialized successfully")
            } else {
                Log.w(TAG, "FaceAnalyzer initialization failed, face analysis will be disabled")
            }

            Log.d(TAG, "Face recognition initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize face recognition", e)
        }
    }

    fun isInitialized(): Boolean {
        return faceDetector != null && faceRecognizer != null
    }

    fun setListener(listener: FaceRecognitionListener?) {
        this.listener = listener
    }

    fun setPreviewSurface(surface: Surface?) {
        val oldSurface = this.previewSurface
        this.previewSurface = surface
        
        if (isRecognizing && oldSurface != surface) {
            Log.d(TAG, "Preview surface changed, restarting capture session")
            restartCaptureSession()
        }
    }

    fun setTestFGIviewEnabled(enabled: Boolean) {
        val oldEnabled = this.testFGIviewEnabled
        this.testFGIviewEnabled = enabled
        
        if (isRecognizing && oldEnabled != enabled) {
            Log.d(TAG, "testFGIviewEnabled changed, restarting capture session")
            restartCaptureSession()
        }
    }

    private fun restartCaptureSession() {
        if (!isRecognizing || cameraDevice == null) {
            return
        }

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
            startCaptureSession()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart capture session", e)
        }
    }

    fun enterRegisterMode(name: String, role: Int = 1, listener: FaceRegisterListener) {
        isRegisterMode = true
        registerName = name
        registerRole = role
        registerListener = listener
        Log.d(TAG, "Entered register mode for: $name, role: $role, isRegisterMode = $isRegisterMode")
    }

    private fun exitRegisterMode() {
        isRegisterMode = false
        registerName = null
        registerRole = 1
        registerListener = null
        Log.d(TAG, "Exited register mode")
    }

    private fun loadFaceDatabase() {
        Log.d(TAG, "loadFaceDatabase called")
        val db = faceDBHelper
        val recognizer = faceRecognizer

        Log.d(TAG, "faceDBHelper is null: ${db == null}")
        Log.d(TAG, "faceRecognizer is null: ${recognizer == null}")

        if (db == null || recognizer == null) {
            Log.e(TAG, "Cannot load face database: helper or recognizer is null")
            return
        }

        try {
            val faces = db.getAllFaceFeatures()
            Log.d(TAG, "Loaded ${faces.size} faces from database")
            for ((name, feature) in faces) {
                Log.d(TAG, "Adding face to recognizer: $name")
                recognizer.addFaceToDatabase(name, feature)
            }
            Log.d(TAG, "Successfully loaded ${faces.size} faces from database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face database", e)
        }
    }

    private fun saveFaceToDatabase(name: String, feature: FloatArray, bitmap: Bitmap?, role: Int = 1) {
        Log.d(TAG, "saveFaceToDatabase called for: $name, role: $role")
        val db = faceDBHelper
        Log.d(TAG, "faceDBHelper is null: ${db == null}")

        try {
            val result = db?.insertFace(name, feature, bitmap, role)
            Log.d(TAG, "insertFace result: $result")
            if (result != null && result > 0) {
                Log.d(TAG, "Saved face to database successfully: $name with role: $role")
            } else {
                Log.e(TAG, "Failed to save face to database: $name, result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save face to database", e)
        }
    }

    fun startRecognition() {
        if (isRecognizing) {
            Log.w(TAG, "Recognition is already running")
            return
        }

        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        initialize()

        if (faceDetector == null || faceRecognizer == null) {
            Log.e(TAG, "Face recognition not initialized")
            return
        }

        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraManager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d(TAG, "Camera $CAMERA_ID opened successfully")
                    startCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.e(TAG, "Camera disconnected")
                    cleanupCameraResources()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cleanupCameraResources()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            cleanupCameraResources()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera $CAMERA_ID", e)
            cleanupCameraResources()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            cleanupCameraResources()
        }
    }

    private fun startCaptureSession() {
        val camera = cameraDevice ?: return

        try {
            val characteristics = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraCharacteristics = characteristics.getCameraCharacteristics(CAMERA_ID)
            val streamConfigMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            val supportedSizes = streamConfigMap?.getOutputSizes(ImageFormat.YUV_420_888)
            Log.d(TAG, "相机支持的YUV_420_888尺寸列表:")
            supportedSizes?.forEach { size ->
                Log.d(TAG, "  ${size.width}x${size.height}")
            }
            
            val targetSize = supportedSizes?.firstOrNull { 
                it.width == 640 && it.height == 480 
            } ?: supportedSizes?.minByOrNull { 
                Math.abs(it.width - 640) + Math.abs(it.height - 480) 
            }
            
            Log.d(TAG, "目标尺寸: 640x480, 实际使用尺寸: ${targetSize?.width}x${targetSize?.height}")
            
            imageReader = ImageReader.newInstance(targetSize?.width ?: 640, targetSize?.height ?: 480, ImageFormat.YUV_420_888, 2)
            Log.d(TAG, "ImageReader创建完成，尺寸: ${imageReader?.width}x${imageReader?.height}")
            
            imageReader?.setOnImageAvailableListener({ reader ->
                if (isCameraClosing) {
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage()
                if (image != null) {
                   // Log.d(TAG, "收到图像: ${image.width}x${image.height}")
                    processImage(image)
                    image.close()
                }
            }, backgroundHandler)

            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader!!.surface)
            
            if (testFGIviewEnabled && previewSurface != null) {
                captureRequestBuilder.addTarget(previewSurface!!)
                Log.d(TAG, "添加预览Surface到CaptureRequest")
            }
            
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
            
            val sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensorSize != null) {
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorSize)
                Log.d(TAG, "设置SCALER_CROP_REGION为传感器全尺寸: ${sensorSize.width()}x${sensorSize.height()}")
            }
            
            Log.d(TAG, "CaptureRequest配置:")
            Log.d(TAG, "  模板: TEMPLATE_STILL_CAPTURE")
            Log.d(TAG, "  CONTROL_MODE: AUTO")
            Log.d(TAG, "  CONTROL_VIDEO_STABILIZATION_MODE: OFF")
            Log.d(TAG, "  CONTROL_ZOOM_RATIO: 1.0")
            Log.d(TAG, "  目标Surface尺寸: ${imageReader?.width}x${imageReader?.height}")
            Log.d(TAG, "  testFGIviewEnabled: $testFGIviewEnabled")
            Log.d(TAG, "  previewSurface: ${if (previewSurface != null) "已设置" else "未设置"}")
            
            val surfaces = mutableListOf(imageReader!!.surface)
            if (testFGIviewEnabled && previewSurface != null) {
                surfaces.add(previewSurface!!)
            }
            
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val captureRequest = captureRequestBuilder.build()
                        session.setRepeatingRequest(captureRequest, null, backgroundHandler)
                        isRecognizing = true
                        Log.d(TAG, "Capture session started")
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Failed to start capture session", e)
                        stopRecognition()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure capture session")
                    stopRecognition()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
            stopRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
            stopRecognition()
        }
    }

    private fun processImage(image: Image) {
        if (!isRecognizing) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRecognitionTime < RECOGNITION_INTERVAL) {
            return
        }

        lastRecognitionTime = currentTime

        val detector = faceDetector ?: return
        val recognizer = faceRecognizer ?: return

        //Log.d(TAG, "processImage: isRegisterMode = $isRegisterMode, registerName = $registerName")

        try {
            val bitmap = imageToBitmap(image)
            if (bitmap == null) {
                Log.e(TAG, "Failed to convert Image to Bitmap")
                return
            }

            val faces = detector.detectFromBitmap(bitmap)

            if (faces.isEmpty()) {
                mainHandler.post {
                    if (isRegisterMode) {
                        Log.d(TAG, "processImage: register mode - no face detected")
                        registerListener?.onFaceRegisterFailed("未检测到人脸，请正对镜头")
                    } else {
                        listener?.onFaceNotDetected()
                    }
                }
                return
            }
            if((System.currentTimeMillis()/100)%50L==0L)
                Log.d(TAG, "processImage: detected ${faces.size} faces")

            if (isRegisterMode) {
                Log.d(TAG, "processImage: entering register mode logic")
                val name = registerName
                if (name != null) {
                    val feature = recognizer.extractFeatureFromBitmap(bitmap, faces[0])
                    if (feature != null) {
                        val recognitionResult = recognizer.recognizeFace(feature)
                        
                        if (recognitionResult != null && recognitionResult.similarity >= REGISTER_SIMILARITY_THRESHOLD) {
                            Log.d(TAG, "Face already registered: ${recognitionResult.name}, similarity: ${recognitionResult.similarity}")
                            recognizer.addFaceToDatabase(name, feature)
                            val updated = faceDBHelper?.updateFace(name, feature, bitmap, registerRole)
                            Log.d(TAG, "Update face result: $updated")
                        } else {
                            Log.d(TAG, "New face, registering: $name")
                            recognizer.addFaceToDatabase(name, feature)
                            saveFaceToDatabase(name, feature, bitmap, registerRole)
                        }
                        
                        Log.d(TAG, "Face registered successfully: $name")
                        mainHandler.post {
                            registerListener?.onFaceRegisterSuccess(name)
                            exitRegisterMode()
                        }
                    } else {
                        Log.d(TAG, "processImage: failed to extract feature")
                        mainHandler.post {
                            registerListener?.onFaceRegisterFailed("人脸特征提取失败，请正对镜头")
                        }
                    }
                }
            } else if(hasRegisteredFaces()){
                var bestResult: RecognitionResult? = null
                var bestSimilarity = 0f
                var bestFaceIndex = -1  // 记录最佳识别结果对应的人脸索引

                for ((index, face) in faces.withIndex()) {
                    val feature = recognizer.extractFeatureFromBitmap(bitmap, face)
                    if (feature != null) {
                        val result = recognizer.recognizeFace(feature)
                        if (result != null && result.similarity > bestSimilarity) {
                            bestSimilarity = result.similarity
                            bestResult = result
                            bestFaceIndex = index  // 记录最佳识别结果对应的人脸索引
                        }
                    }
                }

                if (bestResult != null && bestResult.isRecognized) {
                    if((System.currentTimeMillis()/100)%50L==0L)
                        Log.d(TAG, "Face recognized: ${bestResult.name}, similarity: ${bestResult.similarity}")
                    
                    val name = bestResult.name
                    val currentTime = System.currentTimeMillis()
                    
                    val record = faceRecognitionRecords.getOrPut(name) { FaceRecognitionRecord(name) }
                    
                    record.timestamps.add(currentTime)
                    //只保留距离现在时间3min内的识别结果
                    record.timestamps.removeIf { currentTime - it > ACTIVE_TRIGGER_WINDOW }
                    
                    if (record.timestamps.size >= ACTIVE_TRIGGER_COUNT) {
                        //Log.d(TAG, "Active trigger reached for: $name, count: ${record.timestamps.size}")
                        mainHandler.post {
                            listener?.onActiveTrigger(name, record.timestamps.size)
                        }
                        record.timestamps.clear()
                    }
                    
                    // 只对成功识别的人脸进行角度和唇动分析
                    val analyzer = faceAnalyzer
                    var faceAnalysisResult: FaceAnalyzer.FaceAnalysisResult? = null
                    
                    if (analyzer != null && bestFaceIndex >= 0) {
                        try {
                            // 使用最佳识别结果对应的人脸，而不是 faces[0]
                            val bestFace = faces[bestFaceIndex]
                            val faceRect = android.graphics.Rect(
                                bestFace.x, 
                                bestFace.y, 
                                bestFace.x + bestFace.width, 
                                bestFace.y + bestFace.height
                            )
                            faceAnalysisResult = analyzer.analyze(bitmap, faceRect)
                            
                            // 检查分析结果是否有效
                            if (faceAnalysisResult == null || 
                                !faceAnalysisResult.faceDetected ||
                                faceAnalysisResult.faceDirectionResult.isFacing == -1) {
                                Log.d(TAG, "Face analysis failed or invalid result: ${bestResult.name}")
                                mainHandler.post {
                                    listener?.onFaceNotDetected()
                                }
                                return
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error analyzing face", e)
                            mainHandler.post {
                                listener?.onFaceNotDetected()
                            }
                            return
                        }
                    }
                    
                    mainHandler.post {
                        val isFacing = faceAnalysisResult?.faceDirectionResult?.isFacing == 1
                        val lipMovementDetected = faceAnalysisResult?.lipMovementResult?.isSpeaking ?: false
                        // 使用最佳识别结果对应的人脸，而不是 faces[0]
                        val bestFace = if (bestFaceIndex >= 0) faces[bestFaceIndex] else faces[0]
                        val faceRect = android.graphics.Rect(
                            bestFace.x, 
                            bestFace.y, 
                            bestFace.x + bestFace.width, 
                            bestFace.y + bestFace.height
                        )
                        val faceAngle = faceAnalysisResult?.faceDirectionResult?.yaw
                        listener?.onFaceDetected(bestResult, isFacing, lipMovementDetected, faceRect, faceAngle)
                    }
                } else {
                    mainHandler.post {
                        listener?.onFaceNotDetected()
                    }
                }
            }
            else{//未绑定注册人脸模式
                val analyzer = faceAnalyzer
                var faceAnalysisResult: FaceAnalyzer.FaceAnalysisResult? = null

                if (analyzer != null) {
                    try {
                        val bestFace = faces[0]
                        val faceRect = android.graphics.Rect(
                            bestFace.x,
                            bestFace.y,
                            bestFace.x + bestFace.width,
                            bestFace.y + bestFace.height
                        )
                        faceAnalysisResult = analyzer.analyze(bitmap, faceRect)

                        // 检查分析结果是否有效
                        if (faceAnalysisResult == null ||
                            !faceAnalysisResult.faceDetected ||
                            faceAnalysisResult.faceDirectionResult.isFacing == -1) {
                            Log.d(TAG, "Face analysis failed or invalid result: 未注册人脸模式")
                            mainHandler.post {
                                listener?.onFaceNotDetected()
                            }
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error analyzing face", e)
                        mainHandler.post {
                            listener?.onFaceNotDetected()
                        }
                        return
                    }
                }

                mainHandler.post {
                    val isFacing = faceAnalysisResult?.faceDirectionResult?.isFacing == 1
                    val lipMovementDetected = faceAnalysisResult?.lipMovementResult?.isSpeaking ?: false
                    val bestFace = faces[0]
                    val faceRect = android.graphics.Rect(
                        bestFace.x,
                        bestFace.y,
                        bestFace.x + bestFace.width,
                        bestFace.y + bestFace.height
                    )
                    val faceAngle = faceAnalysisResult?.faceDirectionResult?.yaw
                    var bestResult: RecognitionResult = RecognitionResult("unRegisterMod",0f)
                    listener?.onFaceDetected(bestResult, isFacing, lipMovementDetected, faceRect, faceAngle)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            if (isCameraClosing) {
                Log.d(TAG, "Camera is closing, skipping image conversion")
                return null
            }

            val width = image.width
            val height = image.height

            val yPlane = image.planes[0].buffer
            val uPlane = image.planes[1].buffer
            val vPlane = image.planes[2].buffer

            val ySize = yPlane.remaining()
            val uSize = uPlane.remaining()
            val vSize = vPlane.remaining()

            val nv21 = ByteArray(width * height * 3 / 2)

            yPlane.rewind()
            yPlane.get(nv21, 0, ySize)

            val uPixelStride = image.planes[1].pixelStride
            val vPixelStride = image.planes[2].pixelStride
            val uRowStride = image.planes[1].rowStride
            val vRowStride = image.planes[2].rowStride

            var uvIndex = ySize
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val uIndex = row * uRowStride + col * uPixelStride
                    val vIndex = row * vRowStride + col * vPixelStride

                    if (uvIndex + 1 < nv21.size) {
                        nv21[uvIndex++] = vPlane.get(vIndex)
                        nv21[uvIndex++] = uPlane.get(uIndex)
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
            val jpegData = outputStream.toByteArray()

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            
            if (bitmap != null && bitmap.config != Bitmap.Config.ARGB_8888) {
                val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (argbBitmap != bitmap) {
                    bitmap.recycle()
                }
                return argbBitmap
            }
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Image to Bitmap", e)
            return null
        }
    }

    fun stopRecognition() {
        if (!isRecognizing) {
            return
        }

        isRecognizing = false
        cleanupCameraResources()
        Log.d(TAG, "Face recognition stopped")
    }

    private fun cleanupCameraResources() {
        isCameraClosing = true

        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            captureSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture session", e)
        }

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for image processing to complete", e)
        }

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader", e)
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }

        stopBackgroundThread()

        cameraCloseLock.lock()
        try {
            isCameraClosing = false
            cameraClosedCondition.signalAll()
        } finally {
            cameraCloseLock.unlock()
        }

        Log.d(TAG, "Camera resources cleaned up")
    }

    fun waitForCameraRelease(timeoutMs: Long = 3000): Boolean {
        cameraCloseLock.lock()
        try {
            return cameraClosedCondition.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for camera release", e)
            return false
        } finally {
            cameraCloseLock.unlock()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackgroundThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    fun getDatabaseSize(): Int {
        return faceRecognizer?.getDatabaseSize() ?: 0
    }

    fun hasRegisteredFaces(): Boolean {
        return faceDBHelper?.hasRegisteredFaces() ?: false
    }

    fun getUserRole(name: String): Int? {
        return faceDBHelper?.getFaceRole(name)
    }

    fun clearFaceRecognitionRecords(name: String? = null) {
        if (name != null) {
            faceRecognitionRecords.remove(name)
            Log.d(TAG, "Cleared face recognition records for: $name")
        } else {
            faceRecognitionRecords.clear()
            Log.d(TAG, "Cleared all face recognition records")
        }
    }

    fun addFace(name: String, bitmap: Bitmap): Boolean {
        val detector = faceDetector ?: return false
        val recognizer = faceRecognizer ?: return false

        try {
            val faces = detector.detectFromBitmap(bitmap)
            if (faces.isEmpty()) {
                Log.e(TAG, "No face detected in the image")
                return false
            }

            val feature = recognizer.extractFeatureFromBitmap(bitmap, faces[0])
            if (feature == null) {
                Log.e(TAG, "Failed to extract face feature")
                return false
            }

            recognizer.addFaceToDatabase(name, feature)
            saveFaceToDatabase(name, feature, bitmap)
            Log.d(TAG, "Face added successfully: $name")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add face", e)
            return false
        }
    }

    fun clearDatabase() {
        faceRecognizer?.clearDatabase()
        faceDBHelper?.clearAllFaces()
        Log.d(TAG, "Face database cleared")
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun release() {
        stopRecognition()

        faceDetector?.release()
        faceDetector = null

        faceRecognizer?.release()
        faceRecognizer = null

        faceAnalyzer?.release()
        faceAnalyzer = null

        listener = null

        Log.d(TAG, "Face recognition released")
    }
}
