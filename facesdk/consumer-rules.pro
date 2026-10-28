# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep FaceSDK classes
-keep class com.hexdeploy.facesdk.** { *; }

# Keep data classes
-keep class com.hexdeploy.facesdk.FaceInfo { *; }
-keep class com.hexdeploy.facesdk.RecognitionResult { *; }
