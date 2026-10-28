#ifndef _FACE_DETECTOR_H_
#define _FACE_DETECTOR_H_

#include "ifacedetector.h"
#include "hexdeploy/vision.h"
#include <memory>

class FaceDetector : public IFaceDetector{
    public:
        explicit FaceDetector(){
            initialModel("encrypt_model_det.bin");
        }

        explicit FaceDetector(const std::string& path)
        {
            initialModel(path);
        }

        virtual ~FaceDetector(){
            destroyModel();
        }

        std::vector<IvRect> detectFace(int width, int height, uint8_t* yuv) override;
        std::vector<IvRect> detectFaceRGB(int width, int height, uint8_t* rgb) override;

        void outputYUV(cv::Mat mat);
        void outputNV12(cv::Mat mat);
        void outputBgr(cv::Mat mat);
        void outputRgb(cv::Mat mat);

        static FaceDetector& getSingletonInstance(){
            static FaceDetector instance;
            return instance;
        }

    private:
        void initialModel(const std::string& path);

        void destroyModel();

        std::unique_ptr<hexdeploy::vision::facedet::SCRFD> _infermod;
};

#endif //_FACE_DETECTOR_H_