package com.minrray.voicectl.core.speaker

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerRecognition
import com.minrray.voicectl.core.network.WebSocketHandler

interface SpeakerIdentificationListener {
    fun onSpeakerRegistered(name: String)
    fun onSpeakerRegistrationFailed(reason: String)
    fun onSpeakerRegistrationProgress(progress: Int, maxProgress: Int) {}
}

class SpeakerIdentificationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SpeakerIdentificationManager"
        private const val SPEAKER_IDENTIFICATION_THRESHOLD = 0.5f

        @Volatile
        private var instance: SpeakerIdentificationManager? = null

        fun getInstance(context: Context): SpeakerIdentificationManager {
            return instance ?: synchronized(this) {
                instance ?: SpeakerIdentificationManager(context.applicationContext).also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.release()
                instance = null
                Log.d(TAG, "SpeakerIdentificationManager instance reset")
            }
        }
    }

    private var isInitialized = false
    private var isRegistering = false
    private var isRegisteringAudioWindow = false
    private var registerName: String? = null
    private var registerListener: SpeakerIdentificationListener? = null

    private val audioBuffer = mutableListOf<Float>()
    private val embeddingBuffer = mutableListOf<FloatArray>()
    private val SAMPLE_RATE = 16000
    private val MIN_REGISTER_AUDIO_DURATION_MS = 1000L
    private val MIN_REGISTER_AUDIO_SAMPLES = (SAMPLE_RATE * MIN_REGISTER_AUDIO_DURATION_MS / 1000).toInt()
    private val MAX_REGISTER_AUDIO_DURATION_MS = 30000L
    private val MAX_REGISTER_AUDIO_SAMPLES = (SAMPLE_RATE * MAX_REGISTER_AUDIO_DURATION_MS / 1000).toInt()

    private val speakerDBHelper = SpeakerDBHelper(context)

    private val identificationAudioBuffer = mutableListOf<FloatArray>()
    private val IDENTIFICATION_AUDIO_DURATION_MS = 1500L
    private val IDENTIFICATION_AUDIO_SAMPLES = (SAMPLE_RATE * IDENTIFICATION_AUDIO_DURATION_MS / 1000).toInt()
    private val IDENTIFICATION_INTERVAL_MS = 100L
    private val IDENTIFICATION_INTERVAL_SAMPLES = (SAMPLE_RATE * IDENTIFICATION_INTERVAL_MS / 1000).toInt()
    private var identificationSampleCount = 0
    private var isBufferWarmedUp = false
    private val MIN_WARMUP_SAMPLES = (SAMPLE_RATE * 1000 / 1000).toInt()

    var registerProgress = 0
        private set

    fun initialize(): Boolean {
        if (isInitialized) {
            Log.d(TAG, "Speaker identification already initialized")
            return true
        }

        try {
            SpeakerRecognition.initExtractor(context.assets)
            isInitialized = SpeakerRecognition.isInitialized()
            
            if (isInitialized) {
                Log.d(TAG, "Speaker identification initialized successfully")
                
                loadSpeakerData()
                
                Log.d(TAG, "Registered speakers count: ${SpeakerRecognition.manager.numSpeakers()}")
            } else {
                Log.e(TAG, "Speaker identification initialization failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speaker identification", e)
            isInitialized = false
        }

        return isInitialized
    }

    private fun loadSpeakerData() {
        try {
            val speakers = speakerDBHelper.getAllSpeakerEmbeddings()
            var loadedCount = 0
            for ((name, embeddings) in speakers) {
                val success = SpeakerRecognition.manager.add(name, embeddings)
                if (success) {
                    loadedCount++
                    Log.d(TAG, "Loaded speaker: $name with ${embeddings.size} embeddings")
                }
            }
            Log.d(TAG, "Loaded $loadedCount speakers from database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load speaker data", e)
        }
    }

    private fun saveSpeakerData(name: String, embeddings: List<FloatArray>) {
        try {
            speakerDBHelper.insertSpeaker(name, embeddings)
            Log.d(TAG, "Saved speaker $name to database with ${embeddings.size} embeddings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save speaker data: $name", e)
        }
    }



    fun isReady(): Boolean {
        return isInitialized && SpeakerRecognition.isInitialized()
    }

    fun getRegisteredSpeakers(): List<String> {
        if (!isReady()) {
            Log.e(TAG, "Speaker identification not ready")
            return emptyList()
        }

        try {
            return SpeakerRecognition.manager.allSpeakerNames().toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get registered speakers", e)
            return emptyList()
        }
    }

    fun getSpeakerCount(): Int {
        if (!isReady()) {
            return 0
        }

        try {
            return SpeakerRecognition.manager.numSpeakers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get speaker count", e)
            return 0
        }
    }

    fun hasRegisteredSpeakers(): Boolean {
        return getSpeakerCount() > 0
    }

    fun isRegistering(): Boolean {
        return isRegistering
    }

    fun getRegisterStatus(): RegisterStatus {
        return RegisterStatus(
            isRegistering = isRegistering,
            registerName = registerName,
            progress = registerProgress,
            maxProgress = 5,
            hasMinimumEmbeddings = embeddingBuffer.size >= 1
        )
    }

    data class RegisterStatus(
        val isRegistering: Boolean,
        val registerName: String?,
        val progress: Int,
        val maxProgress: Int,
        val hasMinimumEmbeddings: Boolean
    )

    fun enterRegisterMode(name: String, listener: SpeakerIdentificationListener) {
        if (!isReady()) {
            Log.e(TAG, "Speaker identification not ready for registration")
            listener.onSpeakerRegistrationFailed("声纹识别未初始化")
            return
        }

        if (isRegistering) {
            Log.w(TAG, "Already in register mode")
            listener.onSpeakerRegistrationFailed("正在注册中，请稍后再试")
            return
        }

        isRegistering = true
        isRegisteringAudioWindow = false
        registerName = name
        registerListener = listener
        audioBuffer.clear()
        embeddingBuffer.clear()
        registerProgress = 0

        Log.d(TAG, "Entered register mode for: $name, waiting for isSendingRealTimeAudio to become true")

        val maxEmbeddings = 5
        listener.onSpeakerRegistrationProgress(0, maxEmbeddings)

        Thread {
            try {
                val wsHandler = WebSocketHandler.getInstance()
                var lastSendingState = wsHandler.isSendingRealTimeAudio()
                var hasStartedSending = false

                while (isRegistering) {
                    val currentSendingState = wsHandler.isSendingRealTimeAudio()

                    if (currentSendingState && !lastSendingState) {
                        isRegisteringAudioWindow = true
                        hasStartedSending = true
                        Log.d(TAG, "声纹注册音频采集窗口已开启")
                    }

                    if (!currentSendingState && lastSendingState && hasStartedSending) {
                        Log.d(TAG, "声纹注册音频采集窗口已关闭，检查是否采集足够音频")
                        isRegisteringAudioWindow = false

                        if (embeddingBuffer.size < 1) {
                            Log.w(TAG, "未采集到足够的声纹特征，注册失败")
                            val listener = registerListener
                            exitRegisterMode()
                            listener?.onSpeakerRegistrationFailed("未采集到足够的语音数据，请重新说话")
                            return@Thread
                        }
                    }

                    lastSendingState = currentSendingState
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "监控 isSendingRealTimeAudio 状态失败: ${e.message}", e)
            }
        }.start()
    }

    fun processAudio(audioData: FloatArray): String? {
        if (!isReady()) {
            return null
        }

        return if (isRegistering) {
            processRegisterAudio(audioData)
        } else {
            processIdentifyAudio(audioData)
        }
    }

    private fun processIdentifyAudio(audioData: FloatArray): String? {
        identificationAudioBuffer.add(audioData)
        identificationSampleCount += audioData.size

        val maxChunks = IDENTIFICATION_AUDIO_SAMPLES / audioData.size
        if (identificationAudioBuffer.size > maxChunks) {
            identificationAudioBuffer.removeAt(0)
        }

        if (!isBufferWarmedUp) {
            val totalSamples = identificationAudioBuffer.sumOf { it.size }
            if (totalSamples >= MIN_WARMUP_SAMPLES) {
                isBufferWarmedUp = true
                Log.d(TAG, "识别缓冲区预热完成，已累积 ${totalSamples} 个采样点")
            } else {
                return null
            }
        }

        if (identificationSampleCount >= IDENTIFICATION_INTERVAL_SAMPLES) {
            identificationSampleCount = 0
            return identifySpeaker()
        }

        return null
    }

    private fun processRegisterAudio(audioData: FloatArray): String? {
        if (!isRegisteringAudioWindow) {
            return null
        }

        audioBuffer.addAll(audioData.toList())

        if (audioBuffer.size >= MIN_REGISTER_AUDIO_SAMPLES) {
            val samples = audioBuffer.take(MIN_REGISTER_AUDIO_SAMPLES).toFloatArray()
            audioBuffer.clear()

            return extractEmbedding(samples)
        }

        return null
    }

    private fun extractEmbedding(samples: FloatArray): String? {
        try {
            val stream = SpeakerRecognition.extractor.createStream()
            stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
            stream.inputFinished()

            if (SpeakerRecognition.extractor.isReady(stream)) {
                val embedding = SpeakerRecognition.extractor.compute(stream)
                embeddingBuffer.add(embedding)
                registerProgress = embeddingBuffer.size

                Log.d(TAG, "Extracted embedding ${embeddingBuffer.size}, current buffer size: ${audioBuffer.size}")

                val maxEmbeddings = 5
                registerListener?.onSpeakerRegistrationProgress(registerProgress, maxEmbeddings)

                if (embeddingBuffer.size >= maxEmbeddings) {
                    return registerSpeaker()
                }
            } else {
                Log.e(TAG, "Speaker embedding extractor not ready")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract embedding", e)
        }

        return null
    }

    private fun registerSpeaker(): String? {
        val name = registerName ?: return null

        try {
            if (embeddingBuffer.isEmpty()) {
                Log.e(TAG, "No embeddings to register, continue collecting audio")
                return null
            }

            val alreadyRegistered = speakerDBHelper.getSpeaker(name) != null
            if (alreadyRegistered) {
                Log.d(TAG, "Speaker $name already registered, skipping registration")
                
                val listener = registerListener
                exitRegisterMode()
                
                listener?.onSpeakerRegistered(name)
                return name
            }

            val success = SpeakerRecognition.manager.add(name, embeddingBuffer.toTypedArray())

            if (success) {
                Log.d(TAG, "Speaker registered successfully: $name with ${embeddingBuffer.size} embeddings")
                Log.d(TAG, "Total registered speakers: ${SpeakerRecognition.manager.numSpeakers()}")
                
                saveSpeakerData(name, embeddingBuffer.toList())

                val listener = registerListener
                exitRegisterMode()

                listener?.onSpeakerRegistered(name)

                return name
            } else {
                Log.e(TAG, "Failed to register speaker: $name, continue collecting audio")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register speaker: $name, continue collecting audio", e)
            return null
        }
    }

    private fun identifySpeaker(): String? {
        try {
            val stream = SpeakerRecognition.extractor.createStream()
            
            for (sampleChunk in identificationAudioBuffer) {
                stream.acceptWaveform(samples = sampleChunk, sampleRate = SAMPLE_RATE)
            }
            stream.inputFinished()

            if (SpeakerRecognition.extractor.isReady(stream)) {
                val embedding = SpeakerRecognition.extractor.compute(stream)
                val speakerName = SpeakerRecognition.manager.search(
                    embedding = embedding,
                    threshold = SPEAKER_IDENTIFICATION_THRESHOLD
                )

                if (speakerName.isNotEmpty()) {
                  //  Log.d(TAG, "Identified speaker: $speakerName")
                    return speakerName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to identify speaker", e)
        }

        return null
    }

    fun verifySpeaker(name: String, audioData: FloatArray): Boolean {
        if (!isReady()) {
            return false
        }

        try {
            val stream = SpeakerRecognition.extractor.createStream()
            stream.acceptWaveform(audioData, sampleRate = SAMPLE_RATE)
            stream.inputFinished()

            if (SpeakerRecognition.extractor.isReady(stream)) {
                val embedding = SpeakerRecognition.extractor.compute(stream)
                val isMatch = SpeakerRecognition.manager.verify(
                    name = name,
                    embedding = embedding,
                    threshold = SPEAKER_IDENTIFICATION_THRESHOLD
                )

                Log.d(TAG, "Verify speaker $name: $isMatch")
                return isMatch
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify speaker: $name", e)
        }

        return false
    }

    fun removeSpeaker(name: String): Boolean {
        if (!isReady()) {
            Log.e(TAG, "Speaker identification not ready")
            return false
        }

        try {
            SpeakerRecognition.manager.remove(name)
            speakerDBHelper.deleteSpeaker(name)
            Log.d(TAG, "Speaker removed: $name")
            Log.d(TAG, "Remaining speakers: ${SpeakerRecognition.manager.numSpeakers()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove speaker: $name", e)
            return false
        }
    }

    fun clearAllSpeakers(): Boolean {
        if (!isReady()) {
            Log.e(TAG, "Speaker identification not ready")
            return false
        }

        try {
            val speakers = getRegisteredSpeakers()
            for (speaker in speakers) {
                SpeakerRecognition.manager.remove(speaker)
            }
            speakerDBHelper.clearAllSpeakers()
            Log.d(TAG, "All speakers cleared")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all speakers", e)
            return false
        }
    }

    private fun exitRegisterMode() {
        isRegistering = false
        isRegisteringAudioWindow = false
        registerName = null
        registerListener = null
        audioBuffer.clear()
        embeddingBuffer.clear()
        registerProgress = 0
        identificationAudioBuffer.clear()
        identificationSampleCount = 0
        isBufferWarmedUp = false
        Log.d(TAG, "Exited register mode")
    }

    fun cancelRegistration() {
        if (isRegistering) {
            Log.d(TAG, "Registration cancelled")
            exitRegisterMode()
        }
    }

    fun finishRegistration(): String? {
        if (!isRegistering) {
            Log.w(TAG, "Not in register mode")
            return null
        }

        if (embeddingBuffer.isEmpty()) {
            Log.w(TAG, "No embeddings to register")
            return null
        }

        return registerSpeaker()
    }

    fun release() {
        try {
            cancelRegistration()
            identificationAudioBuffer.clear()
            identificationSampleCount = 0
            isBufferWarmedUp = false
            SpeakerRecognition.release()
            isInitialized = false
            Log.d(TAG, "Speaker identification released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release speaker identification", e)
        }
    }
}
