#include "FaceDetectorJni.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <memory>
#include "aisdk/facedetector.h"
#include "aisdk/ifacedetector.h"

#define LOG_TAG "FaceDetectorJNI"

#define ENABLE_FACE_DETECTOR_LOG 0

#if ENABLE_FACE_DETECTOR_LOG
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...)
#define LOGE(...)
#endif

// 全局人脸检测器实例
static IFaceDetector* gFaceDetector = nullptr;

// 初始化人脸检测器
extern "C" JNIEXPORT jint JNICALL
Java_com_hexdeploy_facesdk_FaceDetector_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath) {

    if (gFaceDetector) {
        LOGI("FaceDetector already initialized");
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
        // 使用工厂函数创建 FaceDetector (参考 aisdkdemo)
        LOGI("Calling createFaceDetector(%s)...", modelPathStr);
        void* detectorPtr = createFaceDetector(modelPathStr);
        if (detectorPtr == nullptr) {
            LOGE("createFaceDetector returned nullptr");
            env->ReleaseStringUTFChars(modelPath, modelPathStr);
            return -1;
        }

        gFaceDetector = reinterpret_cast<IFaceDetector*>(detectorPtr);
        LOGI("FaceDetector initialized successfully with model: %s", modelPathStr);
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return 0;
    } catch (const std::exception& e) {
        LOGE("Failed to initialize FaceDetector: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, modelPathStr);
        return -1;
    }
}

// 检测人脸 - 从 NV21 数据
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hexdeploy_facesdk_FaceDetector_nativeDetectFromNV21(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray nv21Data,
    jint width,
    jint height) {

    if (!gFaceDetector) {
        LOGE("FaceDetector not initialized");
        return nullptr;
    }

    jbyte* nv21Bytes = env->GetByteArrayElements(nv21Data, nullptr);
    jsize nv21Length = env->GetArrayLength(nv21Data);

    // NV21 转 NV12 (交换 U 和 V 的顺序)
    std::vector<uint8_t> nv12Data(nv21Length);
    int ySize = width * height;
    int uvSize = ySize / 4;

    // 复制 Y 分量
    memcpy(nv12Data.data(), nv21Bytes, ySize);

    // 交换 UV 分量 (NV21: VUVU... -> NV12: UVUV...)
    for (int i = 0; i < uvSize; i++) {
        nv12Data[ySize + 2 * i] = nv21Bytes[ySize + i + uvSize];  // U
        nv12Data[ySize + 2 * i + 1] = nv21Bytes[ySize + i];      // V
    }

    // 调用检测
    std::vector<IvRect> faces = gFaceDetector->detectFace(width, height, nv12Data.data());

    env->ReleaseByteArrayElements(nv21Data, nv21Bytes, JNI_ABORT);

    // 转换结果到 Java 对象数组
    jclass faceInfoClass = env->FindClass("com/hexdeploy/facesdk/FaceInfo");
    if (!faceInfoClass) {
        LOGE("Failed to find FaceInfo class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(faceInfoClass, "<init>", "(IIIIF)V");
    if (!constructor) {
        LOGE("Failed to find FaceInfo constructor");
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(faces.size(), faceInfoClass, nullptr);
    for (size_t i = 0; i < faces.size(); i++) {
        jobject faceObj = env->NewObject(faceInfoClass, constructor,
            faces[i]._x, faces[i]._y, faces[i]._width, faces[i]._height, faces[i]._detScore);
        env->SetObjectArrayElement(result, i, faceObj);
        env->DeleteLocalRef(faceObj);
    }

    env->DeleteLocalRef(faceInfoClass);

    LOGI("Detected %zu faces", faces.size());
    return result;
}

// 检测人脸 - 从 Bitmap
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hexdeploy_facesdk_FaceDetector_nativeDetectFromBitmap(
    JNIEnv* env,
    jobject /* this */,
    jobject bitmap) {

    if (!gFaceDetector) {
        LOGE("FaceDetector not initialized");
        return nullptr;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }

    LOGI("Bitmap info: width=%d, height=%d, stride=%d, format=%d",
         info.width, info.height, info.stride, info.format);

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

    LOGI("Starting face detection on %dx%d image using RGB interface", info.width, info.height);

    // 使用 RGB 接口直接检测人脸，避免 YUV 转换
    std::vector<IvRect> faces = gFaceDetector->detectFaceRGB(info.width, info.height, rgbMat.data);

    LOGI("Detected %zu faces from bitmap", faces.size());

    // 转换结果到 Java 对象数组
    jclass faceInfoClass = env->FindClass("com/hexdeploy/facesdk/FaceInfo");
    if (!faceInfoClass) {
        LOGE("Failed to find FaceInfo class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(faceInfoClass, "<init>", "(IIIIF)V");
    if (!constructor) {
        LOGE("Failed to find FaceInfo constructor");
        env->DeleteLocalRef(faceInfoClass);
        return nullptr;
    }

    jobjectArray result = env->NewObjectArray(faces.size(), faceInfoClass, nullptr);

    for (size_t i = 0; i < faces.size(); i++) {
        jobject faceObj = env->NewObject(faceInfoClass, constructor,
            faces[i]._x, faces[i]._y, faces[i]._width, faces[i]._height, faces[i]._detScore);
        env->SetObjectArrayElement(result, i, faceObj);
        env->DeleteLocalRef(faceObj);
        LOGI("Face %zu: x=%d, y=%d, w=%d, h=%d, score=%f",
             i, faces[i]._x, faces[i]._y, faces[i]._width, faces[i]._height, faces[i]._detScore);
    }

    env->DeleteLocalRef(faceInfoClass);

    return result;
}

// 释放资源
extern "C" JNIEXPORT void JNICALL
Java_com_hexdeploy_facesdk_FaceDetector_nativeRelease(
    JNIEnv* env,
    jobject /* this */) {

    if (gFaceDetector) {
        delete gFaceDetector;
        gFaceDetector = nullptr;
        LOGI("FaceDetector released");
    }
}
