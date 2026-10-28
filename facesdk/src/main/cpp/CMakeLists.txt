cmake_minimum_required(VERSION 3.18.1)

project("hexdeploy_facesdk")

# 设置 C++ 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 源文件
set(SOURCES
    # JNI 层
    FaceDetectorJni.cpp
    FaceRecognizerJni.cpp
    # AISDK 层
    aisdk/facedetector.cc
    aisdk/facerecognizer.cc
)

# 头文件路径（使用相对路径）
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/aisdk
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# 构建 native 库
add_library(hexdeploy_facesdk SHARED ${SOURCES})

# 查找 Android 库
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# 静态库目录
set(LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a)

# 添加 RKNN 共享库
add_library(rknnrt SHARED IMPORTED)
set_target_properties(rknnrt PROPERTIES
    IMPORTED_LOCATION ${LIB_DIR}/librknnrt.so
)

# 链接静态库（按依赖顺序）
target_link_libraries(hexdeploy_facesdk
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    # RKNN 运行时库（共享库）
    rknnrt
    # HexDeploy 核心库
    ${LIB_DIR}/libhexdeploy.a
    # OpenCV 库
    ${LIB_DIR}/libopencv_core.a
    ${LIB_DIR}/libopencv_imgproc.a
    ${LIB_DIR}/libopencv_imgcodecs.a
    ${LIB_DIR}/libopencv_video.a
    ${LIB_DIR}/libopencv_videoio.a
    ${LIB_DIR}/libopencv_highgui.a
    ${LIB_DIR}/libopencv_calib3d.a
    ${LIB_DIR}/libopencv_features2d.a
    ${LIB_DIR}/libopencv_flann.a
    ${LIB_DIR}/libopencv_ml.a
    ${LIB_DIR}/libopencv_objdetect.a
    ${LIB_DIR}/libopencv_photo.a
    ${LIB_DIR}/libopencv_stitching.a
    ${LIB_DIR}/libopencv_gapi.a
    ${LIB_DIR}/libopencv_dnn.a
    # 第三方库
    ${LIB_DIR}/liblibprotobuf.a
    ${LIB_DIR}/libtbb.a
    ${LIB_DIR}/libade.a
    ${LIB_DIR}/liblibjpeg-turbo.a
    ${LIB_DIR}/liblibpng.a
    ${LIB_DIR}/liblibtiff.a
    ${LIB_DIR}/liblibopenjp2.a
    ${LIB_DIR}/liblibwebp.a
    ${LIB_DIR}/libquirc.a
    ${LIB_DIR}/libIlmImf.a
    ${LIB_DIR}/libyaml-cpp.a
    ${LIB_DIR}/libittnotify.a
    ${LIB_DIR}/libcpufeatures.a
    ${LIB_DIR}/libtegra_hal.a
)

# 链接系统库
target_link_libraries(hexdeploy_facesdk
    z
    m
    log
)

# 输出信息
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ Standard: ${CMAKE_CXX_STANDARD}")
message(STATUS "Library Dir: ${LIB_DIR}")
