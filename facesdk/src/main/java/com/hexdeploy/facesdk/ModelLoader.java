package com.hexdeploy.facesdk;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 模型文件加载器
 * 从 assets 复制模型文件到设备存储
 */
public class ModelLoader {
    private static final String TAG = "ModelLoader";

    // 默认模型目录（使用应用外部存储）
    private static String DEFAULT_MODEL_DIR = null;
    private static final String MODELS_DIR = "models";

    /**
     * 获取默认模型目录（应用外部存储）
     *
     * @param context Context
     * @return 模型目录路径
     */
    public static String getDefaultModelDir(Context context) {
        if (DEFAULT_MODEL_DIR == null) {
            // 使用应用外部存储目录
            File modelDir = new File(context.getExternalFilesDir(null), "models");
            DEFAULT_MODEL_DIR = modelDir.getAbsolutePath() + "/";
        }
        return DEFAULT_MODEL_DIR;
    }

    /**
     * 从 assets 提取所有模型文件到设备存储
     *
     * @param context Context
     * @param targetDir 目标目录，如果为 null 则使用默认目录
     * @return 是否成功
     */
    public static boolean extractModels(Context context, String targetDir) {
        if (targetDir == null) {
            targetDir = getDefaultModelDir(context);
        }

        Log.i(TAG, "开始提取模型到目录: " + targetDir);

        try {
            // 确保目标目录存在
            File dir = new File(targetDir);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                Log.i(TAG, "创建模型目录: " + created + " (" + targetDir + ")");
            }

            if (!dir.exists() || !dir.isDirectory()) {
                Log.e(TAG, "无法创建模型目录");
                return false;
            }

            // 提取的模型列表
            String[] models = {
                "encrypt_model_det.bin",
                "encrypt_model_reco.bin",
                "encrypt_model_matting.bin"
            };

            boolean allSuccess = true;
            for (String model : models) {
                Log.d(TAG, "提取模型: " + model);
                boolean success = extractModel(context, model, targetDir);
                if (!success) {
                    Log.e(TAG, "Failed to extract: " + model);
                    allSuccess = false;
                } else {
                    Log.i(TAG, "Extracted: " + model);
                }
            }

            Log.i(TAG, "模型提取" + (allSuccess ? "成功" : "失败"));
            return allSuccess;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract models: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从 assets 提取单个模型文件
     *
     * @param context  Context
     * @param modelName 模型文件名
     * @param targetDir 目标目录
     * @return 是否成功
     */
    public static boolean extractModel(Context context, String modelName, String targetDir) {
        if (targetDir == null) {
            targetDir = getDefaultModelDir(context);
        }

        InputStream in = null;
        OutputStream out = null;

        try {
            // 确保目标目录存在
            File dir = new File(targetDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 打开 assets 中的文件
            in = context.getAssets().open(MODELS_DIR + "/" + modelName);

            // 创建目标文件
            File outputFile = new File(targetDir, modelName);
            out = new FileOutputStream(outputFile);

            // 复制文件
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            Log.i(TAG, "Extracted model: " + modelName + " to " + targetDir);
            return true;

        } catch (IOException e) {
            Log.e(TAG, "Failed to extract model: " + modelName + ", " + e.getMessage());
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close streams: " + e.getMessage());
            }
        }
    }

    /**
     * 检查模型文件是否存在
     *
     * @param modelName 模型文件名
     * @param modelDir 模型目录，如果为 null 则使用默认目录
     * @return 是否存在
     */
    public static boolean isModelExists(String modelName, String modelDir) {
        if (modelDir == null) {
            // 无法使用默认目录，因为没有context
            return false;
        }

        File modelFile = new File(modelDir, modelName);
        return modelFile.exists() && modelFile.canRead();
    }

    /**
     * 检查所有必需的模型是否存在
     *
     * @param modelDir 模型目录
     * @return 是否都存在
     */
    public static boolean areAllModelsExist(String modelDir) {
        if (modelDir == null) {
            return false;
        }

        String[] models = {
            "encrypt_model_det.bin",
            "encrypt_model_reco.bin"
        };

        for (String model : models) {
            if (!isModelExists(model, modelDir)) {
                Log.w(TAG, "Model not found: " + model);
                return false;
            }
        }

        return true;
    }

    /**
     * 获取检测模型路径
     *
     * @param modelDir 模型目录
     * @return 模型完整路径
     */
    public static String getDetectorModelPath(String modelDir) {
        if (modelDir == null) {
            return null;
        }
        return modelDir + "encrypt_model_det.bin";
    }

    /**
     * 获取识别模型路径
     *
     * @param modelDir 模型目录
     * @return 模型完整路径
     */
    public static String getRecognizerModelPath(String modelDir) {
        if (modelDir == null) {
            return null;
        }
        return modelDir + "encrypt_model_reco.bin";
    }

    /**
     * 获取抠图模型路径
     *
     * @param modelDir 模型目录
     * @return 模型完整路径
     */
    public static String getMattingModelPath(String modelDir) {
        if (modelDir == null) {
            return null;
        }
        return modelDir + "encrypt_model_matting.bin";
    }
}
