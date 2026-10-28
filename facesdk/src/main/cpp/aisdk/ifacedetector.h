#ifndef _I_FACE_DETECTOR_H_
#define _I_FACE_DETECTOR_H_

#include <vector>
#include <cstdint>
#include <string>
#include <iostream>

class IvRect{
    public:
    int _x;
    int _y;
    int _width;
    int _height;
    float _detScore;
    float _recoScore;

    std::vector<float> _embeding;

    IvRect(){}
    IvRect(int x, int y, int width, int height):_x(x),_y(y),_width(width),_height(height){}
};

class IFaceDetector{
    public:
        IFaceDetector(){}
        IFaceDetector(const std::string& path){std::cout << " " << path.c_str() << std::endl;}
        virtual ~IFaceDetector(){}

        /**
            * @brief 人脸检测接口函数
            *
            * @param width 输入图片宽度
            * @param height 输入图片高度
            * @param yuv 输入nv12格式的图片数据
            * @return std::vector<IvRect> 返回的人脸信息
        */
        virtual std::vector<IvRect> detectFace(int width, int height, uint8_t* yuv) = 0;

        /**
            * @brief 人脸检测接口函数（RGB格式）
            *
            * @param width 输入图片宽度
            * @param height 输入图片高度
            * @param rgb 输入RGB格式的图片数据（24位RGB）
            * @return std::vector<IvRect> 返回的人脸信息
        */
        virtual std::vector<IvRect> detectFaceRGB(int width, int height, uint8_t* rgb) = 0;

        static IFaceDetector& getInstance();
};

#ifdef __cplusplus
extern "C" {
#endif
    // IFaceDetector* createFaceDetector(const std::string& model);
    void* createFaceDetector(const char* model);
#ifdef __cplusplus
}
#endif



#endif //_I_FACE_DETECTOR_H_
