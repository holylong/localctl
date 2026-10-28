package com.k2fsa.sherpa.onnx.kws.model

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Provider for Keyword Spotting (KWS) model configurations.
 * This object provides pre-configured model settings for various KWS models.
 */
object KwsModelProvider {
    /**
     * Supported KWS model types.
     */
    enum class KwsModelType {
        WENETSPEECH_CHINESE,      // Type 0
        GIGASPEECH_ENGLISH,       // Type 1
    }

    /**
     * Get the model configuration for a specific KWS model type.
     *
     * @param type The model type (see KwsModelType enum)
     * @return OnlineModelConfig for the specified model, or null if type not found
     *
     * Please see https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
     * for a list of pre-trained models.
     */
    fun getModelConfig(type: KwsModelType): OnlineModelConfig? {
        val modelDir: String
        when (type) {
            KwsModelType.WENETSPEECH_CHINESE -> {
                modelDir = "kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
                return OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        decoder = "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        joiner = "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer2",
                )
            }

            KwsModelType.GIGASPEECH_ENGLISH -> {
                modelDir = "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
                return OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        decoder = "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                        joiner = "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    modelType = "zipformer2",
                )
            }
        }
        return null
    }

    /**
     * Get the keywords file path for a specific KWS model type.
     *
     * @param type The model type (see KwsModelType enum)
     * @return Path to the keywords file, or empty string if type not found
     *
     * Caution: The types and modelDir should match those in getModelConfig().
     */
    fun getKeywordsFile(type: KwsModelType): String {
        when (type) {
            KwsModelType.WENETSPEECH_CHINESE -> {
                return "kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/keywords.txt"
            }

            KwsModelType.GIGASPEECH_ENGLISH -> {
                return "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/keywords.txt"
            }
        }
        return ""
    }

    /**
     * Get the model directory for a specific KWS model type.
     *
     * @param type The model type (see KwsModelType enum)
     * @return Path to the model directory, or empty string if type not found
     */
    fun getModelDir(type: KwsModelType): String {
        when (type) {
            KwsModelType.WENETSPEECH_CHINESE -> {
                return "kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
            }

            KwsModelType.GIGASPEECH_ENGLISH -> {
                return "kws/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
            }
        }
        return ""
    }
}
