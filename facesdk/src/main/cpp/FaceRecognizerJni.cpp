#include "aisdk/facerecognizer.h"
#include "aisdk/ifacerecognizer.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <memory>
#include <vector>
#include <map>

#define LOG_TAG "FaceRecognizerJNI"
#if 0
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...)
#define LOGE(...)
#endif
// 全局人脸识别器实例
static IFaceRecognizer* gFaceRecognizer = nullptr;

// 人脸特征库 (使用姓名作为 key)
static std::map<std::string, std::vector<float>> gFaceDatabase;

// 初始化人脸识别器
extern "C" JNIEXPORT jint JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath) {

    if (gFaceRecognizer) {
        LOGI("FaceRecognizer already initialized");
        return 0;
    }

    const char* modelPathStr = env->GetStringUTFChars(modelPath, nullptr);

    // 检查模型文件是否存在
    FILE* fp = fopen(modelPathStr, "rb");
    if (fp == nullptr) {
        LOGE("Model file does not exist or cannot be opened: %s", modelPathStr);
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return -1;
    }

    // 获取文件大小
    fseek(fp, 0, SEEK_END);
    long fileSize = ftell(fp);
    fclose(fp);

    LOGI("Model file: %s, size: %ld bytes", modelPathStr, fileSize);

    try {
        // 使用工厂函数创建 FaceRecognizer (参考 aisdkdemo)
        LOGI("Calling createFaceRecognizer(%s)...", modelPathStr);
        void* recognizerPtr = createFaceRecognizer(modelPathStr);
        if (recognizerPtr == nullptr) {
            LOGE("createFaceRecognizer returned nullptr");
            env->ReleaseStringUTFChars(modelPath, modelPathStr);
            return -1;
        }

        gFaceRecognizer = reinterpret_cast<IFaceRecognizer*>(recognizerPtr);
        LOGI("FaceRecognizer initialized successfully with model: %s", modelPathStr);
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return 0;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize FaceRecognizer: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return -1;
    }
}

// 提取人脸特征 - 从 NV21 数据
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeExtractFeatureFromNV21(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray nv21Data,
    jint width,
    jint height,
    jint faceX,
    jint faceY,
    jint faceWidth,
    jint faceHeight) {

    if (!gFaceRecognizer) {
        LOGE("FaceRecognizer not initialized");
        return nullptr;
    }

    jbyte* nv21Bytes = env->GetByteArrayElements(nv21Data, nullptr);
    jsize nv21Length = env->GetArrayLength(nv21Data);

    // NV21 转 NV12
    std::vector<uint8_t> nv12Data(nv21Length);
    int ySize = width * height;
    int uvSize = ySize / 4;

    memcpy(nv12Data.data(), nv21Bytes, ySize);
    for (int i = 0; i < uvSize; i++) {
        nv12Data[ySize + 2 * i] = nv21Bytes[ySize + i + uvSize];
        nv12Data[ySize + 2 * i + 1] = nv21Bytes[ySize + i];
    }

    env->ReleaseByteArrayElements(nv21Data, nv21Bytes, JNI_ABORT);

    // 提取特征
    IvRect faceRect(faceX, faceY, faceWidth, faceHeight);
    LOGI("Extracting feature from face region: x=%d, y=%d, w=%d, h=%d, image=%dx%d",
         faceX, faceY, faceWidth, faceHeight, width, height);

    std::vector<float> feature = gFaceRecognizer->recoFaceId(faceRect, width, height, nv12Data.data());

    if (feature.empty()) {
        LOGE("Failed to extract face feature");
        return nullptr;
    }

    // 转换到 Java 数组
    jfloatArray result = env->NewFloatArray(feature.size());
    env->SetFloatArrayRegion(result, 0, feature.size(), feature.data());

    LOGI("Extracted feature with %zu dimensions", feature.size());
    return result;
}

// 提取人脸特征 - 从 Bitmap
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeExtractFeatureFromBitmap(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap,
    jint faceX,
    jint faceY,
    jint faceWidth,
    jint faceHeight) {

    if (!gFaceRecognizer) {
        LOGE("FaceRecognizer not initialized");
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }

    void* pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return nullptr;
    }

    // 创建 OpenCV Mat - RGBA 格式
    cv::Mat rgbaMat(info.height, info.width, CV_8UC4, pixels);

    // 转换为 RGB (直接使用 RGB 接口，避免 YUV 转换)
    cv::Mat rgbMat;
    cv::cvtColor(rgbaMat, rgbMat, cv::COLOR_RGBA2RGB);

    AndroidBitmap_unlockPixels(env, bitmap);

    IvRect faceRect(faceX, faceY, faceWidth, faceHeight);
    LOGI("Extracting feature from face region: x=%d, y=%d, w=%d, h=%d, image=%dx%d",
         faceX, faceY, faceWidth, faceHeight, info.width, info.height);

    // 使用 RGB 接口直接提取特征，避免 YUV 转换
    std::vector<float> feature;
    int ret = gFaceRecognizer->recoFaceRGB(faceRect, feature, info.width, info.height, rgbMat.data);

    if (ret != 0 || feature.empty()) {
        LOGE("Failed to extract face feature using RGB interface");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(feature.size());
    env->SetFloatArrayRegion(result, 0, feature.size(), feature.data());

    LOGI("Extracted feature from bitmap with %zu dimensions using RGB interface", feature.size());
    return result;
}

// 计算两个特征的相似度
extern "C" JNIEXPORT jfloat JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeCalculateSimilarity(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray feature1,
    jfloatArray feature2) {

    if (!gFaceRecognizer) {
        LOGE("FaceRecognizer not initialized");
        return 0.0f;
    }

    jfloat* f1 = env->GetFloatArrayElements(feature1, nullptr);
    jfloat* f2 = env->GetFloatArrayElements(feature2, nullptr);
    jsize len1 = env->GetArrayLength(feature1);
    jsize len2 = env->GetArrayLength(feature2);

    std::vector<float> vec1(f1, f1 + len1);
    std::vector<float> vec2(f2, f2 + len2);

    env->ReleaseFloatArrayElements(feature1, f1, JNI_ABORT);
    env->ReleaseFloatArrayElements(feature2, f2, JNI_ABORT);

    float similarity = gFaceRecognizer->calSimlilar(vec1, vec2);
    LOGI("Calculated similarity: %.4f", similarity);

    return similarity;
}

// 人脸入库
extern "C" JNIEXPORT jint JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeRegisterFace(
    JNIEnv* env,
    jobject /* this */,
    jstring name,
    jfloatArray feature) {

    if (!gFaceRecognizer) {
        LOGE("FaceRecognizer not initialized");
        return -1;
    }

    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    jfloat* featureData = env->GetFloatArrayElements(feature, nullptr);
    jsize featureLen = env->GetArrayLength(feature);

    std::vector<float> featureVec(featureData, featureData + featureLen);

    // 保存到人脸库
    gFaceDatabase[nameStr] = featureVec;

    env->ReleaseStringUTFChars(name, nameStr);
    env->ReleaseFloatArrayElements(feature, featureData, JNI_ABORT);

    LOGI("Registered face: %s, total faces in database: %zu", nameStr, gFaceDatabase.size());
    return 0;
}

// 人脸识别 - 1:N 搜索
extern "C" JNIEXPORT jobject JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeRecognizeFace(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray feature) {

    if (!gFaceRecognizer) {
        LOGE("FaceRecognizer not initialized");
        return nullptr;
    }

    if (gFaceDatabase.empty()) {
        LOGE("Face database is empty");
        return nullptr;
    }

    jfloat* featureData = env->GetFloatArrayElements(feature, nullptr);
    jsize featureLen = env->GetArrayLength(feature);

    std::vector<float> targetFeature(featureData, featureData + featureLen);
    env->ReleaseFloatArrayElements(feature, featureData, JNI_ABORT);

    // 在人脸库中搜索最匹配的人脸
    float bestSimilarity = 0.0f;
    std::string bestMatchName = "";

    for (const auto& entry : gFaceDatabase) {
        const std::string& name = entry.first;
        const std::vector<float>& dbFeature = entry.second;

        float similarity = gFaceRecognizer->calSimlilar(targetFeature, dbFeature);

        if (similarity > bestSimilarity) {
            bestSimilarity = similarity;
            bestMatchName = name;
        }
    }

    // 创建识别结果对象
    jclass resultClass = env->FindClass("com/hexdeploy/facesdk/RecognitionResult");
    if (!resultClass) {
        LOGE("Failed to find RecognitionResult class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(Ljava/lang/String;F)V");
    if (!constructor) {
        LOGE("Failed to find RecognitionResult constructor");
        return nullptr;
    }

    jstring nameStr = env->NewStringUTF(bestMatchName.c_str());
    jobject result = env->NewObject(resultClass, constructor, nameStr, bestSimilarity);

    env->DeleteLocalRef(nameStr);
    env->DeleteLocalRef(resultClass);

    LOGI("Recognized face: %s, similarity: %.4f", bestMatchName.c_str(), bestSimilarity);
    return result;
}

// 获取人脸库大小
extern "C" JNIEXPORT jint JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeGetDatabaseSize(
    JNIEnv* env,
    jobject /* this */) {

    return static_cast<jint>(gFaceDatabase.size());
}

// 清空人脸库
extern "C" JNIEXPORT void JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeClearDatabase(
    JNIEnv* env,
    jobject /* this */) {

    gFaceDatabase.clear();
    LOGI("Face database cleared");
}

// 删除指定人脸
extern "C" JNIEXPORT jint JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeRemoveFace(
    JNIEnv* env,
    jobject /* this */,
    jstring name) {

    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    auto it = gFaceDatabase.find(nameStr);

    if (it != gFaceDatabase.end()) {
        gFaceDatabase.erase(it);
        LOGI("Removed face: %s", nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
        return 0;
    } else {
        LOGE("Face not found: %s", nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
        return -1;
    }
}

// 释放资源
extern "C" JNIEXPORT void JNICALL
Java_com_hexdeploy_facesdk_FaceRecognizer_nativeRelease(
    JNIEnv* env,
    jobject /* this */) {

    if (gFaceRecognizer) {
        delete gFaceRecognizer;
        gFaceRecognizer = nullptr;
        LOGI("FaceRecognizer released");
    }
    gFaceDatabase.clear();
}
