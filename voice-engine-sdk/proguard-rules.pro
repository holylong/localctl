# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep all model config classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep JNI methods
-keep class com.k2fsa.sherpa.onnx.** {
    private <methods>;
}
