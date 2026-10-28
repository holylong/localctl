# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep FaceSDK public API
-keep public class com.hexdeploy.facesdk.** {
    public *;
}

# Keep data classes
-keep class com.hexdeploy.facesdk.FaceInfo { *; }
-keep class com.hexdeploy.facesdk.RecognitionResult { *; }
-keepclassmembers class com.hexdeploy.facesdk.FaceInfo {
    public <fields>;
}
-keepclassmembers class com.hexdeploy.facesdk.RecognitionResult {
    public <fields>;
}
