package com.k2fsa.sherpa.onnx.model

/**
 * Provider for Speaker Identification model configurations.
 * This object provides pre-configured model settings for various speaker models.
 */
object SpeakerModelProvider {
    /**
     * Supported speaker model types.
     */
    enum class SpeakerModelType {
        CAMPLUS_CN_EN,           // 3dspeaker speech campplus (Chinese + English)
        ERES2NET_BASE_CN,        // 3dspeaker eres2net base (Chinese)
    }

    /**
     * Get the model filename for a specific speaker model type.
     *
     * @param type The model type (see SpeakerModelType enum)
     * @return Path to the model file (relative to assets directory)
     *
     * Please download the model file from
     * https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
     * and put it inside the assets directory.
     */
    fun getModelPath(type: SpeakerModelType): String {
        when (type) {
            SpeakerModelType.CAMPLUS_CN_EN -> {
                // Supports Chinese and English speakers
                return "speaker/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
            }

            SpeakerModelType.ERES2NET_BASE_CN -> {
                // Chinese only
                return "speaker/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
            }
        }
    }

    /**
     * Get the default model configuration (CamPlus Chinese + English).
     */
    fun getDefaultModelPath(): String {
        return getModelPath(SpeakerModelType.CAMPLUS_CN_EN)
    }
}
