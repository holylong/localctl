package com.minrray.halgo

/**
 * AECM（Acoustic Echo Cancellation for Mobile）类，用于处理移动端回声消除
 */
class NativeLib {

    /**
     * AECM实例指针
     */
    private var aecmInstance: Long = 0

    /**
     * A native method that is implemented by the 'halgo' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    /**
     * 创建AECM实例
     */
    external fun createAecm(): Long

    /**
     * 初始化AECM实例
     */
    external fun initAecm(aecmInst: Long, sampleRate: Int): Int

    /**
     * 设置AECM配置
     */
    external fun setAecmConfig(aecmInst: Long, cngMode: Int, echoMode: Int): Int

    /**
     * 处理音频数据（回声消除）
     */
    external fun processAecm(
        aecmInst: Long,
        nearendNoisy: ShortArray,
        nearendClean: ShortArray?,
        out: ShortArray,
        nrOfSamples: Int,
        msInSndCardBuf: Int
    ): Int

    /**
     * 设置远端音频数据
     */
    external fun bufferFarend(aecmInst: Long, farend: ShortArray, nrOfSamples: Int): Int

    /**
     * 释放AECM实例
     */
    external fun freeAecm(aecmInst: Long)

    /**
     * 初始化AECM
     */
    fun initialize(sampleRate: Int) {
        if (aecmInstance == 0L) {
            aecmInstance = createAecm()
            initAecm(aecmInstance, sampleRate)
            // 设置默认配置：启用CNG，中等回声抑制
            setAecmConfig(aecmInstance, 1, 3)
        }
    }

    /**
     * 处理音频帧，执行回声消除
     */
    fun process(
        nearendNoisy: ShortArray,
        nearendClean: ShortArray?,
        out: ShortArray,
        nrOfSamples: Int,
        msInSndCardBuf: Int = 0
    ): Int {
        return if (aecmInstance != 0L) {
            processAecm(aecmInstance, nearendNoisy, nearendClean, out, nrOfSamples, msInSndCardBuf)
        } else {
            -1 // 未初始化错误
        }
    }

    /**
     * 添加远端音频数据
     */
    fun addFarend(farend: ShortArray, nrOfSamples: Int): Int {
        return if (aecmInstance != 0L) {
            bufferFarend(aecmInstance, farend, nrOfSamples)
        } else {
            -1 // 未初始化错误
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        if (aecmInstance != 0L) {
            freeAecm(aecmInstance)
            aecmInstance = 0L
        }
    }

    companion object {
        // Used to load the 'halgo' library on application startup.
        init {
            System.loadLibrary("halgo")
        }
    }
}