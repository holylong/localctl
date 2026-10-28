#include "facedetector.h"
#include "fileutil.h"
#include "hexdeploy/vision.h"
#include <opencv2/imgproc.hpp>
#include <memory>
#include <string>

void* createFaceDetector(const char* model)
{
	return new FaceDetector(model);
}


#if 0
void* createFaceDetectorInternal(const char* model)
{
	return new FaceDetector(model);
}

IFaceDetector* createFaceDetector(const std::string& model)
{
    return reinterpret_cast<IFaceDetector*>(createFaceDetectorInternal(model.c_str())); 
}
#endif

void FaceDetector::outputYUV(cv::Mat mat)
{
    std::ofstream ofs;
    std::stringstream ss;
    ss << mat.cols;
    ss << "_";
    ss << mat.rows;
    ss << "_yuv.yuv";
    ofs.open(ss.str(), std::ios::binary);

    cv::Mat yuvImage;
    cv::cvtColor(mat, yuvImage, cv::COLOR_BGR2YUV);

    if(ofs.is_open()){
        ofs.write(reinterpret_cast<char*>(yuvImage.data), yuvImage.total() * yuvImage.elemSize());
        ofs.flush();
        ofs.close();
    }
}

void swapYUV_I420toNV12(unsigned char* i420bytes, unsigned char* nv12bytes, int width, int height)
{
    int nLenY = width * height;
    int nLenU = nLenY / 4;

    memcpy(nv12bytes, i420bytes, width * height);

    for (int i = 0; i < nLenU; i++)
    {
        nv12bytes[nLenY + 2 * i] = i420bytes[nLenY + i];                    // U
        nv12bytes[nLenY + 2 * i + 1] = i420bytes[nLenY + nLenU + i];        // V
    }
}

void BGR2YUV_nv12(cv::Mat src, cv::Mat &dst)
{
    int w_img = src.cols;
    int h_img = src.rows;
    dst = cv::Mat(h_img*1.5, w_img, CV_8UC1, cv::Scalar(0));
    cv::Mat src_YUV_I420(h_img*1.5, w_img, CV_8UC1, cv::Scalar(0));  //YUV_I420
    cv::cvtColor(src, src_YUV_I420, cv::COLOR_BGR2YUV_I420);
    swapYUV_I420toNV12(src_YUV_I420.data, dst.data, w_img, h_img);
}


void FaceDetector::outputNV12(cv::Mat mat)
{
    int cropWidth = mat.cols/2*2;
    int cropHeight = mat.rows/2*2;

    cv::Mat cropImage = mat(cv::Rect(0 , 0, cropWidth, cropHeight));

    std::ofstream ofs;
    std::stringstream ss;
    ss << cropImage.cols;
    ss << "_";
    ss << cropImage.rows;
    ss << "_nv12.yuv";
    ofs.open(ss.str(), std::ios::binary);

    // 创建一个YUV图像来存储转换后的数据
    cv::Mat yuvImage(cropImage.rows *3/2, cropImage.cols, CV_8UC1, cv::Scalar(0)); // Y分量
    cv::Mat uvImage(cropImage.rows *3/ 2, cropImage.cols, CV_8UC1, cv::Scalar(0)); // UV分量 (NV12)

    // 进行BGR到YUV颜色空间转换
    cv::cvtColor(cropImage, yuvImage, cv::COLOR_BGR2YUV_I420);

    memcpy(uvImage.data, yuvImage.data, cropImage.cols*cropImage.rows);

    int yLen  = cropImage.cols * cropImage.rows;
    int uvLen = cropImage.cols*cropImage.rows/4;
    // 将UV分量（U和V）从I420格式提取并排列为NV12格式
    for (int el = 0; el < uvLen; el++) {
        uvImage.data[yLen + 2*el] = yuvImage.data[yLen + el];
        uvImage.data[yLen + 2*el + 1] = yuvImage.data[yLen + el + uvLen];
    } 

    if(ofs.is_open()){
        // 写入YUV数据到文件
        ofs.write(reinterpret_cast<char*>(uvImage.data), uvImage.total() * uvImage.elemSize());
        // ofs.write(reinterpret_cast<char*>(uvImage.data), cropImage.cols*cropImage.rows*3/2);
        ofs.flush();
        ofs.close();
    }
}

void FaceDetector::outputBgr(cv::Mat mat)
{
    std::ofstream ofs;
    std::stringstream ss;
    ss << mat.cols;
    ss << "_";
    ss << mat.rows;
    ss << "_bgr.yuv";
    ofs.open(ss.str(), std::ios::binary);
    if(ofs.is_open()){
        // ofs.write((char*)rgbImage.data, width*height*3);
        ofs.write(reinterpret_cast<char*>(mat.data), mat.total() * mat.elemSize());
        ofs.flush();
        ofs.close();
    }
}

void FaceDetector::outputRgb(cv::Mat mat)
{
    std::ofstream ofs;
    std::stringstream ss;
    ss << mat.cols;
    ss << "_";
    ss << mat.rows;
    ss << "_rgb.yuv";
    ofs.open(ss.str(), std::ios::binary);

    cv::Mat rgbImage;
    cv::cvtColor(mat, rgbImage, cv::COLOR_BGR2RGB);  //deraise blue men

    if(ofs.is_open()){
        // ofs.write((char*)rgbImage.data, width*height*3);
        ofs.write(reinterpret_cast<char*>(rgbImage.data), rgbImage.total() * rgbImage.elemSize());
        ofs.flush();
        ofs.close();
    }
}


std::vector<IvRect> FaceDetector::detectFace(int width, int height, uint8_t* yuv)
{
    cv::Mat yuvImg(height*3/2, width, CV_8UC1, yuv);

    // read file to Mat
    // yuvImg.read((char *)yuvImg.data, width*height*3/2);

    cv::Mat rgbImg;
    cv::cvtColor(yuvImg, rgbImg, cv::COLOR_YUV2RGB_NV12);

    // outputBgr(im);
    // outputRgb(im);
    // outputYUV(im);
    // outputNV12(im);

    // cv::cvtColor(im, im, cv::COLOR_BGR2GRAY);

    hexdeploy::vision::FaceDetectionResult result;
    if (!_infermod->Predict(&rgbImg, &result)) {
        std::cerr << "Failed to predict." << std::endl;
        return std::vector<IvRect>();
    }

    std::vector<IvRect> ivrs;
    for (size_t i = 0; i < result.boxes.size(); ++i) {
        IvRect tvr;
        tvr._x = result.boxes[i][0];
        tvr._y = result.boxes[i][1];
        tvr._width = result.boxes[i][2] - result.boxes[i][0];
        tvr._height = result.boxes[i][3] - result.boxes[i][1];
        tvr._detScore = result.scores[i];

        ivrs.push_back(tvr);
    }

    return ivrs;
}

std::vector<IvRect> FaceDetector::detectFaceRGB(int width, int height, uint8_t* rgb)
{
    // Directly use RGB data without YUV conversion
    cv::Mat rgbImg(height, width, CV_8UC3, rgb);

    hexdeploy::vision::FaceDetectionResult result;
    if (!_infermod->Predict(&rgbImg, &result)) {
        std::cerr << "Failed to predict." << std::endl;
        return std::vector<IvRect>();
    }

    std::vector<IvRect> ivrs;
    for (size_t i = 0; i < result.boxes.size(); ++i) {
        IvRect tvr;
        tvr._x = result.boxes[i][0];
        tvr._y = result.boxes[i][1];
        tvr._width = result.boxes[i][2] - result.boxes[i][0];
        tvr._height = result.boxes[i][3] - result.boxes[i][1];
        tvr._detScore = result.scores[i];

        ivrs.push_back(tvr);
    }

    return ivrs;
}

void FaceDetector::initialModel(const std::string& path){
    std::string params_file;
    auto option = hexdeploy::RuntimeOption();
    option.UseRKNPU2(hexdeploy::rknpu2::CpuName::RK3588, hexdeploy::rknpu2::CoreMask::RKNN_NPU_CORE_AUTO);
    auto format = hexdeploy::ModelFormat::RKNN;


    // 直接使用传入的完整路径，不添加前缀
    std::string fullModelPath = path;

    std::cout << "Loading model from: " << fullModelPath.c_str() << std::endl;

    if(!FileUtil::isFileExist(fullModelPath)) {
        std::cerr << "ERROR: Model file does not exist: " << fullModelPath.c_str() << std::endl;
        return;
    }

    std::cout << "Model file exists, initializing..." << std::endl;

    _infermod = std::make_unique<hexdeploy::vision::facedet::SCRFD>(fullModelPath, params_file, option, format);

    if (!_infermod->Initialized()) {
        std::cerr << "Failed to initialize SCRFD model." << std::endl;
        return;
    }else{
        std::cout << "SCRFD model initialized successfully" << std::endl;
    }
    _infermod->DisableNormalize();
    _infermod->DisablePermute();
}

void FaceDetector::destroyModel()
{

}

IFaceDetector& IFaceDetector::getInstance(){
    return FaceDetector::getSingletonInstance();
}
