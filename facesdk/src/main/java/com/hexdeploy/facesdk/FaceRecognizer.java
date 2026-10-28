package com.hexdeploy.facesdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 人脸识别器
 * 提供 1:1 人脸比对和 1:N 人脸识别功能
 */
public class FaceRecognizer {
    private static final String TAG = "FaceRecognizer";
    private static final float DEFAULT_THRESHOLD = 0.5f; // 默认相似度阈值

    private boolean initialized = false;
    private float threshold = DEFAULT_THRESHOLD;

    // 人脸特征库（从数据库加载）
    private List<FaceDatabaseEntry> faceDatabase = new ArrayList<>();

    static {
        // 使用统一的库加载器（已在 FaceDetector 中加载）
    }

    // JNI 方法
    private native int nativeInit(String modelPath);
    private native float[] nativeExtractFeatureFromNV21(byte[] nv21Data, int width, int height,
                                                        int faceX, int faceY, int faceWidth, int faceHeight);
    private native float[] nativeExtractFeatureFromBitmap(Bitmap bitmap,
                                                         int faceX, int faceY, int faceWidth, int faceHeight);
    private native float nativeCalculateSimilarity(float[] feature1, float[] feature2);

    /**
     * 初始化人脸识别器
     *
     * @param modelPath RKNN 模型文件路径
     * @return 是否成功
     */
    public boolean init(String modelPath) {
        if (initialized) {
            Log.w(TAG, "FaceRecognizer already initialized");
            return true;
        }

        int result = nativeInit(modelPath);
        if (result == 0) {
            initialized = true;
            Log.i(TAG, "FaceRecognizer initialized successfully");
            return true;
        } else {
            Log.e(TAG, "Failed to initialize FaceRecognizer");
            return false;
        }
    }

    /**
     * 初始化识别器（自动从 assets 加载模型）
     *
     * @param context  Context
     * @param modelDir 模型目标目录，如果为 null 则使用默认目录
     * @return 是否成功
     */
    public boolean init(Context context, String modelDir) {
        // 提取模型文件（如果还未提取）
        if (!ModelLoader.areAllModelsExist(modelDir)) {
            boolean extracted = ModelLoader.extractModels(context, modelDir);
            if (!extracted) {
                Log.e(TAG, "Failed to extract models from assets");
                return false;
            }
        }

        // 初始化识别器
        String modelPath = ModelLoader.getRecognizerModelPath(modelDir);
        return init(modelPath);
    }

    /**
     * 初始化识别器（自动从 assets 加载模型到默认目录）
     *
     * @param context Context
     * @return 是否成功
     */
    public boolean init(Context context) {
        return init(context, ModelLoader.getDefaultModelDir(context));
    }

    /**
     * 从数据库加载人脸库
     *
     * @param context Context
     */
    public void loadDatabase(Context context) {
        // TODO: 从数据库加载人脸库
        Log.i(TAG, "Loading face database from SQLite");
        faceDatabase.clear();
    }

    /**
     * 提取人脸特征 - 从 NV21 数据
     *
     * @param nv21Data 相机预览数据
     * @param width    图像宽度
     * @param height   图像高度
     * @param face     人脸位置
     * @return 人脸特征向量 (128 维或 512 维，取决于模型)
     */
    public float[] extractFeatureFromNV21(byte[] nv21Data, int width, int height, FaceInfo face) {
        if (!initialized) {
            Log.e(TAG, "FaceRecognizer not initialized");
            return null;
        }

        // 扩展人脸区域以提高特征质量
        FaceInfo expandedFace = FaceDetector.expandFace(face, width, height);

        return nativeExtractFeatureFromNV21(nv21Data, width, height,
                expandedFace.x, expandedFace.y, expandedFace.width, expandedFace.height);
    }

    /**
     * 提取人脸特征 - 从 Bitmap
     *
     * @param bitmap 输入图像
     * @param face   人脸位置
     * @return 人脸特征向量
     */
    public float[] extractFeatureFromBitmap(Bitmap bitmap, FaceInfo face) {
        if (!initialized) {
            Log.e(TAG, "FaceRecognizer not initialized");
            return null;
        }

        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Invalid bitmap");
            return null;
        }

        // 扩展人脸区域
        FaceInfo expandedFace = FaceDetector.expandFace(face, bitmap.getWidth(), bitmap.getHeight());

        return nativeExtractFeatureFromBitmap(bitmap,
                expandedFace.x, expandedFace.y, expandedFace.width, expandedFace.height);
    }

    /**
     * 计算两个人脸特征的相似度
     *
     * @param feature1 人脸特征 1
     * @param feature2 人脸特征 2
     * @return 相似度 (0.0 - 1.0)，值越大越相似
     */
    public float calculateSimilarity(float[] feature1, float[] feature2) {
        if (!initialized) {
            Log.e(TAG, "FaceRecognizer not initialized");
            return 0.0f;
        }

        if (feature1 == null || feature2 == null) {
            Log.e(TAG, "Invalid features");
            return 0.0f;
        }

        return nativeCalculateSimilarity(feature1, feature2);
    }

    /**
     * 判断两个人脸是否匹配
     *
     * @param feature1 人脸特征 1
     * @param feature2 人脸特征 2
     * @return 是否匹配
     */
    public boolean isMatch(float[] feature1, float[] feature2) {
        float similarity = calculateSimilarity(feature1, feature2);
        return similarity >= threshold;
    }

    /**
     * 人脸识别 - 1:N 搜索
     *
     * @param feature 待识别的人脸特征
     * @return 识别结果 (包含人名和相似度)
     */
    public RecognitionResult recognizeFace(float[] feature) {
        if (!initialized) {
            Log.e(TAG, "FaceRecognizer not initialized");
            return new RecognitionResult(null, 0.0f);
        }

        if (feature == null) {
            Log.e(TAG, "Invalid feature");
            return new RecognitionResult(null, 0.0f);
        }

        // 在内存数据库中搜索
        String bestMatchName = null;
        float bestSimilarity = 0.0f;

        for (FaceDatabaseEntry entry : faceDatabase) {
            float similarity = calculateSimilarity(feature, entry.feature);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatchName = entry.name;
            }
        }

        // 如果相似度低于阈值，返回未知
        if (bestSimilarity < threshold) {
            //Log.i(TAG, "Face not recognized, best similarity: " + bestSimilarity);
            return new RecognitionResult(null, bestSimilarity);
        }

      //  Log.i(TAG, "Face recognized as: " + bestMatchName + ", similarity: " + bestSimilarity);
        return new RecognitionResult(bestMatchName, bestSimilarity);
    }

    /**
     * 添加人脸到内存数据库
     *
     * @param name    人名
     * @param feature 人脸特征
     */
    public void addFaceToDatabase(String name, float[] feature) {
        faceDatabase.add(new FaceDatabaseEntry(name, feature));
        Log.i(TAG, "Added face to database: " + name + ", total: " + faceDatabase.size());
    }

    /**
     * 获取人脸库大小
     */
    public int getDatabaseSize() {
        return faceDatabase.size();
    }

    /**
     * 清空人脸库
     */
    public void clearDatabase() {
        faceDatabase.clear();
        Log.i(TAG, "Face database cleared");
    }

    /**
     * 删除指定人脸
     *
     * @param name 人名
     * @return 是否成功
     */
    public boolean removeFace(String name) {
        boolean removed = false;
        for (int i = faceDatabase.size() - 1; i >= 0; i--) {
            if (faceDatabase.get(i).name.equals(name)) {
                faceDatabase.remove(i);
                removed = true;
            }
        }

        if (removed) {
            Log.i(TAG, "Removed face: " + name);
        } else {
            Log.e(TAG, "Failed to remove face: " + name);
        }
        return removed;
    }

    /**
     * 设置识别阈值
     *
     * @param threshold 阈值 (0.0 - 1.0)，默认 0.5
     */
    public void setThreshold(float threshold) {
        this.threshold = Math.max(0.0f, Math.min(1.0f, threshold));
        Log.i(TAG, "Threshold set to: " + this.threshold);
    }

    /**
     * 获取识别阈值
     */
    public float getThreshold() {
        return threshold;
    }

    /**
     * 释放资源
     */
    public void release() {
        if (initialized) {
            // Note: 不再调用 nativeRelease()，因为它在旧版本中会清空数据库
            // faceDatabase.clear();
            initialized = false;
            Log.i(TAG, "FaceRecognizer released");
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 人脸数据库条目
     */
    public static class FaceDatabaseEntry {
        public String name;
        public float[] feature;

        public FaceDatabaseEntry(String name, float[] feature) {
            this.name = name;
            this.feature = feature;
        }
    }
}
