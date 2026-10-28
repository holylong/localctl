package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.util.Log

/**
 * Unified facade for Speaker Recognition functionality.
 * Provides access to speaker embedding extraction and management.
 *
 * Please download the model file from
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
 * and put it inside the assets directory.
 */
object SpeakerRecognition {
    var _extractor: SpeakerEmbeddingExtractor? = null
    var _manager: SpeakerEmbeddingManager? = null

    val extractor: SpeakerEmbeddingExtractor
        get() {
            return _extractor!!
        }

    val manager: SpeakerEmbeddingManager
        get() {
            return _manager!!
        }

    /**
     * Initialize the speaker embedding extractor and manager.
     * This method must be called before accessing the extractor or manager.
     *
     * @param assetManager The Android AssetManager for loading models from assets
     * @param modelName Optional custom model name (defaults to 3dspeaker model)
     */
    fun initExtractor(assetManager: AssetManager? = null, modelName: String? = null) {
        synchronized(this) {
            if (_extractor != null) {
                return
            }
            Log.i("sherpa-onnx", "Initializing speaker embedding extractor")

            val model = modelName ?: "speaker/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"

            _extractor = SpeakerEmbeddingExtractor(
                assetManager = assetManager,
                config = SpeakerEmbeddingExtractorConfig(
                    model = model,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                )
            )

            _manager = SpeakerEmbeddingManager(dim = _extractor!!.dim())
        }
    }

    /**
     * Check if the extractor has been initialized.
     */
    fun isInitialized(): Boolean {
        return _extractor != null && _manager != null
    }

    /**
     * Release resources used by the extractor and manager.
     */
    fun release() {
        synchronized(this) {
            _extractor?.release()
            _manager?.release()
            _extractor = null
            _manager = null
        }
    }
}
