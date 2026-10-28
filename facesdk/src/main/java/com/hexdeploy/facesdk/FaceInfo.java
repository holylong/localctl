package com.hexdeploy.facesdk;

/**
 * 人脸信息数据类
 */
public class FaceInfo {
    public int x;          // 人脸左上角 X 坐标
    public int y;          // 人脸左上角 Y 坐标
    public int width;      // 人脸宽度
    public int height;     // 人脸高度
    public float score;    // 检测置信度 (0.0 - 1.0)

    public FaceInfo(int x, int y, int width, int height, float score) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.score = score;
    }

    @Override
    public String toString() {
        return String.format("FaceInfo{x=%d, y=%d, width=%d, height=%d, score=%.2f}",
                x, y, width, height, score);
    }
}
