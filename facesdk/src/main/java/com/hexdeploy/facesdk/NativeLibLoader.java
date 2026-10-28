package com.hexdeploy.facesdk;

import android.util.Log;

/**
 * Native 库加载器
 * 负责按正确顺序加载所有 native 库
 */
public class NativeLibLoader {
    private static final String TAG = "NativeLibLoader";
    private static boolean loaded = false;

    /**
     * 加载所有必需的 native 库
     * 必须在使用 FaceSDK 之前调用
     */
    public static synchronized void loadNativeLibraries() {
        if (loaded) {
            Log.d(TAG, "Native libraries already loaded");
            return;
        }

        try {
            // 1. 先加载 RKNN 运行时库
            System.loadLibrary("rknnrt");
            Log.i(TAG, "RKNN runtime library loaded successfully");

            // 2. 再加载 OpenCV Java 库
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV library loaded successfully");

            // 3. 最后加载 FaceSDK 主库
            System.loadLibrary("hexdeploy_facesdk");
            Log.i(TAG, "FaceSDK native library loaded successfully");

            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries: " + e.getMessage());
            throw new RuntimeException("Failed to load native libraries", e);
        }
    }

    /**
     * 检查库是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }
}
