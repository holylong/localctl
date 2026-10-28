#ifndef FACE_DETECTOR_JNI_H
#define FACE_DETECTOR_JNI_H

#include <jni.h>
#include <string>
#include <vector>
#include "ifacedetector.h"

// 人脸检测结果结构体
struct FaceInfo {
    int x;
    int y;
    int width;
    int height;
    float score;

    FaceInfo() : x(0), y(0), width(0), height(0), score(0.0f) {}
    FaceInfo(int x_, int y_, int w, int h, float s)
        : x(x_), y(y_), width(w), height(h), score(s) {}
};

// JNI 工具函数
class JniUtils {
public:
    static FaceInfo convertToFaceInfo(const IvRect& rect) {
        return FaceInfo(rect._x, rect._y, rect._width, rect._height, rect._detScore);
    }

    static std::vector<FaceInfo> convertToFaceInfoVector(const std::vector<IvRect>& rects) {
        std::vector<FaceInfo> result;
        for (const auto& rect : rects) {
            result.push_back(convertToFaceInfo(rect));
        }
        return result;
    }
};

#endif // FACE_DETECTOR_JNI_H
