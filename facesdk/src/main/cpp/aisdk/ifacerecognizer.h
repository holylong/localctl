#ifndef _I_FACE_RECOGNIZER_H_
#define _I_FACE_RECOGNIZER_H_

#include "ifacedetector.h"
#include <string>
#include <vector>
#include <iostream>


class IFaceRecognizer{
    public:
        IFaceRecognizer(){}
        IFaceRecognizer(const std::string& path){std::cout << " " << path.c_str() << std::endl;}

        virtual ~IFaceRecognizer(){}

        /**
            * @brief  获取多个人脸特征
            *
            * @param boxes 输出参数，包含人脸特征信息
            * @param width
            * @param height
            * @param yuv
            * @return int 成功返回0，失败返回 -1
        */
        virtual int recoFace(std::vector<IvRect>& boxes, int width, int height, uint8_t* yuv) = 0;

        /**
         * @brief 获取单张人脸特征
         *
         * @param box 输出参数包含特征
         * @param width
         * @param height
         * @param yuv
         * @return int
         */
        virtual int recoFace(IvRect& box, int width, int height, uint8_t* yuv) = 0;

        /**
         * @brief 获取单张人脸特征
         *
         * @param box
         * @param embeding 输出参数人脸特征
         * @param width
         * @param height
         * @param yuv
         * @return int 成功0,失败-1
        */
        virtual int recoFace(const IvRect& box, std::vector<float>& embeding, int width, int height, uint8_t* yuv) = 0;

        /**
         * @brief 获取多张人脸特征
         *
         * @param box
         * @param embedings 输出参数,人脸特征顺序与输入一致
         * @param width
         * @param height
         * @param yuv
         * @return int 成功0,失败-1
        */
        virtual int recoFace(const std::vector<IvRect>& box, std::vector<std::vector<float>>& embedings, int width, int height, uint8_t* yuv) = 0;

        /**
            * @brief 人脸特征获取函数
            *
            * @param box 输入人脸的位置
            * @param width 输入图片的宽度
            * @param height 输入图片的高度
            * @param yuv 输入nv12格式的图片数据
            * @return std::vector<float> 返回人脸特征信息
        */
        virtual std::vector<float> recoFaceId(const IvRect& box, int width, int height, uint8_t* yuv) = 0;

        /**
            * @brief 获取单张人脸特征（RGB格式）
            *
            * @param box 输入人脸的位置
            * @param embedding 输出参数人脸特征
            * @param width 输入图片的宽度
            * @param height 输入图片的高度
            * @param rgb 输入RGB格式的图片数据（24位RGB）
            * @return int 成功0,失败-1
        */
        virtual int recoFaceRGB(const IvRect& box, std::vector<float>& embedding, int width, int height, uint8_t* rgb) = 0;


        /**
            * @brief 计算人脸相似度
            *
            * @param embeding1 人脸1特征
            * @param embeding2  人脸2特征
            * @return float 返回相似度 0-1之间，一般认为大于0.5为同一个人，越大相似度越高
        */
        virtual float calSimlilar(const std::vector<float>& embeding1, const std::vector<float>& embeding2) = 0;

        static IFaceRecognizer& getInstance();
};

#ifdef __cplusplus
extern "C" {
#endif
    void* createFaceRecognizer(const char* model);
#ifdef __cplusplus
}
#endif

#endif //_I_FACE_RECOGNIZER_H_
