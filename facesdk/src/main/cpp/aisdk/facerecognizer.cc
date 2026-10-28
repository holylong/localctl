#include "facerecognizer.h"


void* createFaceRecognizer(const char* model)
{
	return new FaceRecognizer(model);
}


#if 0


void* createFaceRecognizerInternal(const char* model)
{
	return new FaceRecognizer(model);
}


// #ifdef __cplusplus
// extern "C" {
// #endif
IFaceRecognizer* createFaceFacognizer(const std::string& model)
{
    return reinterpret_cast<IFaceRecognizer*>(createFaceRecognizerInternal(model.c_str())); 
}
// #ifdef __cplusplus
// }
// #endif
#endif

IFaceRecognizer& IFaceRecognizer::getInstance(){
    return FaceRecognizer::getSingletonInstance();
}

int FaceRecognizer::recoFace(std::vector<IvRect>& boxes, int width, int height, uint8_t* yuv)
{
    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);
    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);
    
    for(auto box : boxes){
        hexdeploy::vision::FaceRecognitionResult res;
        if (!_infermod->Predict(rgbImg(cv::Rect(box._x, box._y, box._width, box._height)), &res)) {
            std::cerr << "Prediction Failed." << std::endl;
            return -1;
        }
        box._embeding = res.embedding;
    }
    return 0;
}

int FaceRecognizer::recoFace(IvRect& box, int width, int height, uint8_t* yuv)
{
    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);
    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);
    
    hexdeploy::vision::FaceRecognitionResult res;
    if (!_infermod->Predict(rgbImg(cv::Rect(box._x, box._y, box._width, box._height)), &res)) {
        std::cerr << "Prediction Failed." << std::endl;
        return -1;
    }

    box._embeding = res.embedding;
    return 0;
}

int FaceRecognizer::recoFace(const IvRect& box, std::vector<float>& embedding, int width, int height, uint8_t* yuv)
{
    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);
    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);
    
    hexdeploy::vision::FaceRecognitionResult res;
    if (!_infermod->Predict(rgbImg(cv::Rect(box._x, box._y, box._width, box._height)), &res)) {
        std::cerr << "Prediction Failed." << std::endl;
        return -1;
    }

    embedding = res.embedding;
    return 0;
}   

int FaceRecognizer::recoFace(const std::vector<IvRect>& boxes, std::vector<std::vector<float>>& embeddings, int width, int height, uint8_t* yuv)
{
    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);
    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);

    std::vector<std::vector<float>> arrFeatures;
    
    for(auto box : boxes){
        hexdeploy::vision::FaceRecognitionResult res;
        if (!_infermod->Predict(rgbImg(cv::Rect(box._x, box._y, box._width, box._height)), &res)) {
            std::cerr << "Prediction Failed." << std::endl;
            return -1;
        }
        arrFeatures.push_back(res.embedding);
    }
    return 0;
}

std::vector<float> FaceRecognizer::recoFaceId(const IvRect& box, int width, int height, uint8_t* yuv)
{
    std::cout << "recoFaceId called with face box: x=" << box._x << ", y=" << box._y
              << ", w=" << box._width << ", h=" << box._height
              << ", image size: " << width << "x" << height << std::endl;

    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);
    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);

    // 裁剪人脸区域
    cv::Rect faceRect(box._x, box._y, box._width, box._height);
    std::cout << "Cropping face region from RGB image: " << faceRect << std::endl;

    cv::Mat faceImg = rgbImg(faceRect);
    std::cout << "Face image size: " << faceImg.rows << "x" << faceImg.cols << " channels=" << faceImg.channels() << std::endl;

    hexdeploy::vision::FaceRecognitionResult res;
    if (!_infermod->Predict(faceImg, &res)) {
        std::cerr << "Prediction Failed." << std::endl;
        return std::vector<float>();
    }

    std::cout << "Feature extracted, dimension: " << res.embedding.size() << std::endl;

    // 打印特征向量的前10个值和后10个值，用于调试
    std::cout << "Feature vector (first 10): ";
    for (size_t i = 0; i < std::min(size_t(10), res.embedding.size()); i++) {
        std::cout << res.embedding[i] << " ";
    }
    std::cout << std::endl;

    // 计算特征向量的L2范数
    float norm = 0.0f;
    for (float val : res.embedding) {
        norm += val * val;
    }
    norm = std::sqrt(norm);
    std::cout << "Feature vector L2 norm: " << norm << std::endl;

    return res.embedding;
}

int FaceRecognizer::recoFaceRGB(const IvRect& box, std::vector<float>& embedding, int width, int height, uint8_t* rgb)
{
    // Directly use RGB data without YUV conversion
    cv::Mat rgbImg(height, width, CV_8UC3, rgb);

    hexdeploy::vision::FaceRecognitionResult res;
    if (!_infermod->Predict(rgbImg(cv::Rect(box._x, box._y, box._width, box._height)), &res)) {
        std::cerr << "Prediction Failed." << std::endl;
        return -1;
    }

    embedding = res.embedding;
    return 0;
}


float FaceRecognizer::calSimlilar(const std::vector<float>& embedding1, const std::vector<float>& embedding2)
{
    std::cout << "Calculating similarity between two embeddings (dim1=" << embedding1.size()
              << ", dim2=" << embedding2.size() << ")" << std::endl;

    float similarity = hexdeploy::vision::utils::CosineSimilarity(
        embedding1, embedding2,
        _infermod->GetPostprocessor().GetL2Normalize());

    std::cout << "Similarity result: " << similarity << std::endl;

    return similarity;
}

void FaceRecognizer::initialModel(const std::string& path)
{
    // 直接使用传入的完整路径，不添加前缀
    std::string fullModelPath = path;

    std::cout << "Loading ArcFace model from: " << fullModelPath.c_str() << std::endl;

    std::string params_file;
    auto option = hexdeploy::RuntimeOption();
    // option.UseRKNPU2(hexdeploy::rknpu2::CpuName::RK3588, hexdeploy::rknpu2::CoreMask::RKNN_NPU_CORE_AUTO);
    option.UseRKNPU2();
    auto format = hexdeploy::ModelFormat::RKNN;

    _infermod = std::make_unique<hexdeploy::vision::faceid::ArcFace>(fullModelPath, params_file, option, format);

    if (_infermod->Initialized()) {
        std::cout << "ArcFace model initialized successfully" << std::endl;
    } else {
        std::cerr << "Failed to initialize ArcFace model." << std::endl;
    }

    _infermod->GetPreprocessor().DisableNormalize();
    _infermod->GetPreprocessor().DisablePermute();
}

void FaceRecognizer::destroyModel()
{

}
