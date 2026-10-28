package com.hexdeploy.facesdk;

/**
 * 人脸识别结果
 */
public class RecognitionResult {
    public String name;        // 识别到的人名 (如果未识别到则为 null)
    public float similarity;   // 相似度 (0.0 - 1.0)

    public RecognitionResult(String name, float similarity) {
        this.name = name;
        this.similarity = similarity;
    }

    public boolean isRecognized() {
        return name != null && !name.isEmpty();
    }

    @Override
    public String toString() {
        if (isRecognized()) {
            return String.format("RecognitionResult{name='%s', similarity=%.2f}", name, similarity);
        } else {
            return String.format("RecognitionResult{name='Unknown', similarity=%.2f}", similarity);
        }
    }
}
