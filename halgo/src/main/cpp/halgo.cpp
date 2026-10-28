#include <jni.h>
#include <string>
#include <android/log.h>
#include "aecm/echo_control_mobile.h"

// 定义日志宏
#define LOG_TAG "HalgoAECM"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_minrray_halgo_NativeLib_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

/**
 * 创建AECM实例
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_minrray_halgo_NativeLib_createAecm(
        JNIEnv* env,
        jobject /* this */) {
    void* aecmInst = WebRtcAecm_Create();
    LOGD("Created AECM instance: %p", aecmInst);
    return reinterpret_cast<jlong>(aecmInst);
}

/**
 * 初始化AECM实例
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_minrray_halgo_NativeLib_initAecm(
        JNIEnv* env,
        jobject /* this */,
        jlong aecmInst,
        jint sampleRate) {
    void* inst = reinterpret_cast<void*>(aecmInst);
    int32_t result = WebRtcAecm_Init(inst, sampleRate);
    LOGD("Initialized AECM with sample rate: %d, result: %d", sampleRate, result);
    return result;
}

/**
 * 设置AECM配置
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_minrray_halgo_NativeLib_setAecmConfig(
        JNIEnv* env,
        jobject /* this */,
        jlong aecmInst,
        jint cngMode,
        jint echoMode) {
    void* inst = reinterpret_cast<void*>(aecmInst);
    AecmConfig config;
    config.cngMode = static_cast<int16_t>(cngMode);
    config.echoMode = static_cast<int16_t>(echoMode);
    int32_t result = WebRtcAecm_set_config(inst, config);
    LOGD("Set AECM config: cngMode=%d, echoMode=%d, result: %d", cngMode, echoMode, result);
    return result;
}

/**
 * 处理音频数据（回声消除）
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_minrray_halgo_NativeLib_processAecm(
        JNIEnv* env,
        jobject /* this */,
        jlong aecmInst,
        jshortArray nearendNoisy,
        jshortArray nearendClean,
        jshortArray out,
        jint nrOfSamples,
        jint msInSndCardBuf) {
    void* inst = reinterpret_cast<void*>(aecmInst);
    
    // 获取输入缓冲区指针
    jshort* nearendNoisyPtr = env->GetShortArrayElements(nearendNoisy, nullptr);
    if (nearendNoisyPtr == nullptr) {
        LOGE("Failed to get nearendNoisy array elements");
        return AECM_NULL_POINTER_ERROR;
    }
    
    jshort* nearendCleanPtr = nullptr;
    if (nearendClean != nullptr) {
        nearendCleanPtr = env->GetShortArrayElements(nearendClean, nullptr);
        if (nearendCleanPtr == nullptr) {
            LOGE("Failed to get nearendClean array elements");
            env->ReleaseShortArrayElements(nearendNoisy, nearendNoisyPtr, JNI_ABORT);
            return AECM_NULL_POINTER_ERROR;
        }
    }
    
    jshort* outPtr = env->GetShortArrayElements(out, nullptr);
    if (outPtr == nullptr) {
        LOGE("Failed to get out array elements");
        env->ReleaseShortArrayElements(nearendNoisy, nearendNoisyPtr, JNI_ABORT);
        if (nearendCleanPtr != nullptr) {
            env->ReleaseShortArrayElements(nearendClean, nearendCleanPtr, JNI_ABORT);
        }
        return AECM_NULL_POINTER_ERROR;
    }
    
    // 调用AECM处理函数
    int32_t result = WebRtcAecm_Process(inst, nearendNoisyPtr, nearendCleanPtr, outPtr, nrOfSamples, msInSndCardBuf);
    
    // 释放缓冲区
    env->ReleaseShortArrayElements(nearendNoisy, nearendNoisyPtr, JNI_ABORT);
    if (nearendCleanPtr != nullptr) {
        env->ReleaseShortArrayElements(nearendClean, nearendCleanPtr, JNI_ABORT);
    }
    env->ReleaseShortArrayElements(out, outPtr, 0);
    
    // LOGD("Processed AECM: samples=%d, delay=%dms, result: %d", nrOfSamples, msInSndCardBuf, result);
    return result;
}

/**
 * 设置远端音频数据
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_minrray_halgo_NativeLib_bufferFarend(
        JNIEnv* env,
        jobject /* this */,
        jlong aecmInst,
        jshortArray farend,
        jint nrOfSamples) {
    void* inst = reinterpret_cast<void*>(aecmInst);
    
    jshort* farendPtr = env->GetShortArrayElements(farend, nullptr);
    if (farendPtr == nullptr) {
        LOGE("Failed to get farend array elements");
        return AECM_NULL_POINTER_ERROR;
    }
    
    int32_t result = WebRtcAecm_BufferFarend(inst, farendPtr, nrOfSamples);
    
    env->ReleaseShortArrayElements(farend, farendPtr, JNI_ABORT);
    
    // LOGD("Buffered farend: samples=%d, result: %d", nrOfSamples, result);
    return result;
}

/**
 * 释放AECM实例
 */
extern "C" JNIEXPORT void JNICALL
Java_com_minrray_halgo_NativeLib_freeAecm(
        JNIEnv* env,
        jobject /* this */,
        jlong aecmInst) {
    void* inst = reinterpret_cast<void*>(aecmInst);
    WebRtcAecm_Free(inst);
    LOGD("Freed AECM instance: %p", inst);
}