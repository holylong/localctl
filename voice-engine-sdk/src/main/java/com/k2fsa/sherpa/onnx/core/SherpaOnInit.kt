package com.k2fsa.sherpa.onnx.core

/**
 * Unified native library loader for Sherpa-ONNX.
 * This object loads the native JNI library once, preventing duplicate loading attempts.
 */
object SherpaOnInit {
    init {
        System.loadLibrary("sherpa-onnx-jni")
    }
}
