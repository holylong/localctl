#ifndef _FACE_RECOGNIZER_H_
#define _FACE_RECOGNIZER_H_

#include "ifacerecognizer.h"
#include "hexdeploy/vision.h"
#include <memory>

class FaceRecognizer : public IFaceRecognizer{
    public:
        FaceRecognizer(){
            initialModel("encrypt_model_reco.bin");
        }

        FaceRecognizer(const std::string& path){
            initialModel(path);
        }

        virtual ~FaceRecognizer(){
            destroyModel();
        }

        virtual int recoFace(std::vector<IvRect>& boxes, int width, int height, uint8_t* yuv);
        virtual int recoFace(IvRect& box, int width, int height, uint8_t* yuv);

        virtual int recoFace(const IvRect& box, std::vector<float>& embedding, int width, int height, uint8_t* yuv);
        virtual int recoFace(const std::vector<IvRect>& boxes, std::vector<std::vector<float>>& embeddings, int width, int height, uint8_t* yuv);
        virtual std::vector<float> recoFaceId(const IvRect& box, int width, int height, uint8_t* yuv);

        // RGB interface
        virtual int recoFaceRGB(const IvRect& box, std::vector<float>& embedding, int width, int height, uint8_t* rgb);

        virtual float calSimlilar(const std::vector<float>& embedding1, const std::vector<float>& embedding2);

        static FaceRecognizer& getSingletonInstance(){
            static FaceRecognizer instance;
            return instance;
        }

    private:
        void initialModel(const std::string& path);

        void destroyModel();

        std::unique_ptr<hexdeploy::vision::faceid::ArcFace> _infermod;
};

#endif //_FACE_RECOGNIZER_H_