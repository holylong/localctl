# FaceSDK Android Library

基于 AISDK 封装的人脸识别 Android SDK，支持人脸检测、特征提取、人脸入库和实时识别。

## 功能特性

- ✅ 人脸检测 - 基于 RKNN 模型，硬件加速
- ✅ 特征提取 - 提取 512 维人脸特征向量
- ✅ 人脸入库 - 将人脸特征保存到本地数据库
- ✅ 实时识别 - 1:N 人脸搜索，支持阈值配置
- ✅ 相似度计算 - 计算两个人脸特征的相似度

## 技术架构

### C++ 层
- 基于 HexDeploy (FastDeploy) 推理框架
- 使用 RK3588 NPU 硬件加速
- 支持 NV21/NV12 格式输入

### JNI 层
- 封装 C++ 接口到 Java
- 内存管理优化
- 多线程安全

### Java 层
- 简洁易用的 API
- 自动内存管理
- 线程安全设计

## 集成方法

### 1. 添加依赖

在您的 app 模块的 `build.gradle.kts` 中：

```kotlin
dependencies {
    implementation(project(":facesdk"))
}
```

### 2. 初始化

```kotlin
val detector = FaceDetector()
val recognizer = FaceRecognizer()

// 初始化检测器
val detInit = detector.init("/vendor/bin/mingriai/encrypt_model_det.bin")

// 初始化识别器
val recInit = recognizer.init("/vendor/bin/mingriai/encrypt_model_rec.bin")
```

### 3. 人脸检测

```kotlin
// 从 Bitmap 检测
val faces = detector.detectFromBitmap(bitmap)

// 从相机数据检测
val faces = detector.detectFromNV21(nv21Data, width, height)

for (face in faces) {
    Log.d("Face", "Found face at (${face.x}, ${face.y}), score: ${face.score}")
}
```

### 4. 人脸入库

```kotlin
// 检测人脸
val faces = detector.detectFromBitmap(bitmap)
if (faces.isNotEmpty()) {
    val face = faces[0]

    // 提取特征
    val feature = recognizer.extractFeatureFromBitmap(bitmap, face)

    // 保存到数据库
    val success = recognizer.registerFace("张三", feature)
}
```

### 5. 人脸识别

```kotlin
// 检测人脸
val faces = detector.detectFromBitmap(bitmap)
if (faces.isNotEmpty()) {
    val face = faces[0]

    // 提取特征
    val feature = recognizer.extractFeatureFromBitmap(bitmap, face)

    // 识别
    val result = recognizer.recognizeFace(feature)

    if (result.isRecognized) {
        Log.d("Recognition", "识别到: ${result.name}, 相似度: ${result.similarity}")
    } else {
        Log.d("Recognition", "未识别")
    }
}
```

### 6. 设置识别阈值

```kotlin
// 默认阈值 0.5，可根据实际情况调整
recognizer.setThreshold(0.55f)
```

## API 文档

### FaceDetector

| 方法 | 说明 |
|------|------|
| `init(modelPath: String): Boolean` | 初始化检测器 |
| `detectFromBitmap(bitmap: Bitmap): List<FaceInfo>` | 从 Bitmap 检测人脸 |
| `detectFromNV21(data: ByteArray, w: Int, h: Int): List<FaceInfo>` | 从 NV21 数据检测人脸 |
| `release()` | 释放资源 |

### FaceRecognizer

| 方法 | 说明 |
|------|------|
| `init(modelPath: String): Boolean` | 初始化识别器 |
| `extractFeatureFromBitmap(bitmap: Bitmap, face: FaceInfo): Float[]` | 提取人脸特征 |
| `extractFeatureFromNV21(data: ByteArray, w: Int, h: Int, face: FaceInfo): Float[]` | 从 NV21 提取特征 |
| `calculateSimilarity(f1: Float[], f2: Float[]): Float` | 计算相似度 |
| `registerFace(name: String, feature: Float[]): Boolean` | 人脸入库 |
| `recognizeFace(feature: Float[]): RecognitionResult` | 人脸识别 |
| `getDatabaseSize(): Int` | 获取人脸库大小 |
| `clearDatabase()` | 清空人脸库 |
| `removeFace(name: String): Boolean` | 删除指定人脸 |
| `setThreshold(threshold: Float)` | 设置识别阈值 |
| `release()` | 释放资源 |

### FaceInfo

```kotlin
class FaceInfo {
    var x: Int          // 左上角 X 坐标
    var y: Int          // 左上角 Y 坐标
    var width: Int      // 人脸宽度
    var height: Int     // 人脸高度
    var score: Float    // 检测置信度
}
```

### RecognitionResult

```kotlin
class RecognitionResult {
    var name: String        // 识别到的人名
    var similarity: Float   // 相似度

    fun isRecognized(): Boolean  // 是否识别成功
}
```

## 性能指标

基于 RK3588 平台测试：

- **人脸检测**: ~30ms (1280x720)
- **特征提取**: ~20ms (单人)
- **1:N 搜索**: ~5ms (100 人库)
- **实时识别**: 支持 20 FPS

## 注意事项

1. **模型路径**: 确保模型文件已部署到 `/vendor/bin/mingriai/` 目录
2. **相机权限**: 需要添加 `android.permission.CAMERA` 权限
3. **线程安全**: FaceDetector 和 FaceRecognizer 不是线程安全的，建议在单线程中使用
4. **内存管理**: 使用完毕后调用 `release()` 释放资源
5. **特征维度**: 默认 512 维，根据模型可能不同
6. **识别阈值**: 默认 0.5，建议根据实际场景调整 (0.45-0.60)

## 依赖项

- Android SDK 24+
- HexDeploy (FastDeploy)
- OpenCV 4.x
- RKNN Toolkit (RK3588)

## 许可证

Apache 2.0

## 联系方式

如有问题请联系项目维护者。
