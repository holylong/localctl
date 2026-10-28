package com.k2fsa.sherpa.onnx

data class EndpointRule(
    var mustContainNonSilence: Boolean,
    var minTrailingSilence: Float,
    var minUtteranceLength: Float,
)

data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f)
)

data class OnlineLMConfig(
    var model: String = "",
    var scale: Float = 0.5f,
)

data class OnlineCtcFstDecoderConfig(
    var graph: String = "",
    var maxActive: Int = 3000,
)
