package com.k2fsa.sherpa.onnx.kws

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
