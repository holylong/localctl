package com.hexdeploy.facesdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 人脸检测器
 * 使用 RKNN 模型进行人脸检测
 */
public class FaceDetector {
    private static final String TAG = "FaceDetector";

    private boolean initialized = false;

    static {
        // 使用统一的库加载器
        NativeLibLoader.loadNativeLibraries();
    }

    /**
     * 初始化人脸检测器
     *
     * @param modelPath RKNN 模型文件路径 (例如: "/vendor/bin/mingriai/encrypt_model_det.bin")
     * @return 0 成功, -1 失败
     */
    public native int nativeInit(String modelPath);

    /**
     * 从 NV21 数据检测人脸
     *
     * @param nv21Data NV21 格式的图像数据
     * @param width    图像宽度
     * @param height   图像高度
     * @return 检测到的人脸列表
     */
    private native FaceInfo[] nativeDetectFromNV21(byte[] nv21Data, int width, int height);

    /**
     * 从 Bitmap 检测人脸
     *
     * @param bitmap 输入图像
     * @return 检测到的人脸列表
     */
    private native FaceInfo[] nativeDetectFromBitmap(Bitmap bitmap);

    /**
     * 释放资源
     */
    public native void nativeRelease();

    /**
     * 初始化检测器
     *
     * @param modelPath 模型路径
     * @return 是否成功
     */
    public boolean init(String modelPath) {
        if (initialized) {
            Log.w(TAG, "FaceDetector already initialized");
            return true;
        }

        int result = nativeInit(modelPath);
        if (result == 0) {
            initialized = true;
            Log.i(TAG, "FaceDetector initialized successfully");
            return true;
        } else {
            Log.e(TAG, "Failed to initialize FaceDetector");
            return false;
        }
    }

    /**
     * 初始化检测器（自动从 assets 加载模型）
     *
     * @param context  Context
     * @param modelDir 模型目标目录，如果为 null 则使用默认目录
     * @return 是否成功
     */
    public boolean init(Context context, String modelDir) {
        // 提取模型文件
        boolean extracted = ModelLoader.extractModels(context, modelDir);
        if (!extracted) {
            Log.e(TAG, "Failed to extract models from assets");
            return false;
        }

        // 初始化检测器
        String modelPath = ModelLoader.getDetectorModelPath(modelDir);
        return init(modelPath);
    }

    /**
     * 初始化检测器（自动从 assets 加载模型到默认目录）
     *
     * @param context Context
     * @return 是否成功
     */
    public boolean init(Context context) {
        return init(context, ModelLoader.getDefaultModelDir(context));
    }

    /**
     * 检测人脸 - 从相机 NV21 数据
     *
     * @param nv21Data 相机预览数据 (NV21 格式)
     * @param width    图像宽度
     * @param height   图像高度
     * @return 检测到的人脸列表
     */
    public List<FaceInfo> detectFromNV21(byte[] nv21Data, int width, int height) {
        if (!initialized) {
            Log.e(TAG, "FaceDetector not initialized");
            return new ArrayList<>();
        }

        FaceInfo[] faces = nativeDetectFromNV21(nv21Data, width, height);
        return faces != null ? java.util.Arrays.asList(faces) : new ArrayList<FaceInfo>();
    }

    /**
     * 检测人脸 - 从 Bitmap
     *
     * @param bitmap 输入图像
     * @return 检测到的人脸列表
     */
    public List<FaceInfo> detectFromBitmap(Bitmap bitmap) {
        if (!initialized) {
            Log.e(TAG, "FaceDetector not initialized");
            return new ArrayList<>();
        }

        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Invalid bitmap");
            return new ArrayList<>();
        }

        FaceInfo[] faces = nativeDetectFromBitmap(bitmap);
        return faces != null ? java.util.Arrays.asList(faces) : new ArrayList<FaceInfo>();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (initialized) {
            nativeRelease();
            initialized = false;
            Log.i(TAG, "FaceDetector released");
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 扩展人脸区域 (用于特征提取)
     * 默认扩展 20%，提高特征提取质量
     *
     * @param face    原始人脸信息
     * @param imgWidth 图像宽度
     * @param imgHeight 图像高度
     * @return 扩展后的人脸信息
     */
    public static FaceInfo expandFace(FaceInfo face, int imgWidth, int imgHeight) {
        float expandRatio = 0.2f; // 扩展 20%

        int expandX = (int) (face.width * expandRatio);
        int expandY = (int) (face.height * expandRatio);

        int newX = Math.max(0, face.x - expandX);
        int newY = Math.max(0, face.y - expandY);
        int newWidth = Math.min(imgWidth - newX, face.width + 2 * expandX);
        int newHeight = Math.min(imgHeight - newY, face.height + 2 * expandY);

        return new FaceInfo(newX, newY, newWidth, newHeight, face.score);
    }
}
