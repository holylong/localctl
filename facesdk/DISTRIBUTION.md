# FaceSDK AAR 独立分发指南

## 项目结构

```
faceidandroid/
├── facesdk/                    # 独立的 Android 库模块（可直接分发）
│   ├── src/main/
│   │   ├── java/               # Java SDK 接口
│   │   ├── cpp/                # JNI 和 AISDK 源码
│   │   │   ├── FaceDetectorJni.cpp
│   │   │   ├── FaceRecognizerJni.cpp
│   │   │   ├── aisdk/          # AISDK 源码（已包含）
│   │   │   └── include/        # HexDeploy 头文件（已包含）
│   │   └── jniLibs/            # 预编译的静态库（已包含）
│   │       └── arm64-v8a/      # RK3588 库文件
│   └── build.gradle.kts        # 库构建配置
└── app/                        # 示例应用
```

## 重要特性

✅ **完全独立** - facesdk 模块包含所有依赖
✅ **无需外部库** - 不需要 libaisdk 或其他预装库
✅ **即插即用** - 编译成 AAR 后可直接分发
✅ **RK3588 专用** - 针对 RK3588 NPU 优化

## 编译 AAR

### 方法 1: 使用 Android Studio

1. 打开项目：`faceidandroid/`
2. 选择菜单：`Build > Build Bundle(s) / APK(s) > Build Bundle(s)`
3. 等待编译完成
4. 生成的 AAR：`facesdk/build/outputs/aar/facesdk-release.aar`

### 方法 2: 使用命令行

```bash
cd faceidandroid

# 编译 Debug 版本
./gradlew :facesdk:assembleDebug

# 编译 Release 版本
./gradlew :facesdk:assembleRelease

# 输出位置：facesdk/build/outputs/aar/
```

## 分发给其他项目

### 方式 1: 作为本地 AAR

1. 将 `facesdk-release.aar` 复制到目标项目的 `app/libs/` 目录
2. 在目标项目的 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(fileTree("libs") { include("*.aar") })
}
```

### 方式 2: 作为模块

1. 复制整个 `facesdk/` 目录到目标项目
2. 在目标项目的 `settings.gradle.kts` 中添加：

```kotlin
include(":facesdk")
project(":facesdk").projectDir = File("facesdk")
```

3. 在 `app/build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation(project(":facesdk"))
}
```

### 方式 3: 发布到 Maven 仓库

```kotlin
// facesdk/build.gradle.kts
plugins {
    id("com.android.library")
    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("aar") {
            from(components["android"])
        }
    }
}
```

然后发布：

```bash
./gradlew :facesdk:publishToMavenLocal
```

## 使用示例

在目标项目中使用 FaceSDK：

### 1. 初始化

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 初始化 FaceSDK
        val detector = FaceDetector()
        val recognizer = FaceRecognizer()

        detector.init("/vendor/bin/mingriai/encrypt_model_det.bin")
        recognizer.init("/vendor/bin/mingriai/encrypt_model_rec.bin")
    }
}
```

### 2. 人脸检测

```kotlin
val faces = detector.detectFromBitmap(bitmap)
for (face in faces) {
    Log.d("Face", "Found: ${face.x}, ${face.y}, score: ${face.score}")
}
```

### 3. 人脸识别

```kotlin
// 提取特征
val feature = recognizer.extractFeatureFromBitmap(bitmap, face)

// 识别人脸
val result = recognizer.recognizeFace(feature)
if (result.isRecognized) {
    Log.d("Recognition", "识别到: ${result.name}")
}
```

## 依赖说明

AAR 包中已包含：

### Native 库
- ✅ libhexdeploy_facesdk.so
- ✅ libhexdeploy.a (静态)
- ✅ libopencv_*.a (静态)
- ✅ 所有第三方静态库

### 运行时依赖
- ⚠️ **librknnrt.so** - 需要设备预装或在运行时加载

RKNN 库由瑞芯微系统提供，通常预装在 `/vendor/lib64/` 目录。

## 设备要求

- ✅ Android 7.0+ (API 24+)
- ✅ ARM 64 位 (arm64-v8a)
- ✅ RK3588 或兼容芯片
- ✅ RKNN Runtime 库

## 常见问题

### 1. 运行时找不到 librknnrt.so

**解决方案**:

```kotlin
// 在 Application 中检查
try {
    System.loadLibrary("rknnrt")
} catch (e: UnsatisfiedLinkError) {
    Log.e("FaceSDK", "RKNN Runtime not found")
}
```

### 2. 模型文件不存在

确保模型文件已部署到设备：

```bash
adb push encrypt_model_det.bin /vendor/bin/mingriai/
adb push encrypt_model_rec.bin /vendor/bin/mingriai/
```

### 3. 权限问题

添加相机权限到 `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

## 性能指标 (RK3588)

| 功能 | 耗时 | FPS |
|------|------|-----|
| 人脸检测 | ~30ms | 33 |
| 特征提取 | ~20ms | 50 |
| 1:N识别 | ~5ms | 200 |

## 技术支持

- 查看 API 文档: `facesdk/README.md`
- 查看示例代码: `app/src/main/java/com/hexdeploy/faceid/`
- 问题反馈: [提交 Issue]

## 版本历史

- **v1.0.0** - 初始版本，支持人脸检测和识别

## 许可证

Apache 2.0

---

**注意**: 此 AAR 仅适用于 RK3588 及兼容平台。其他平台需要重新编译。
