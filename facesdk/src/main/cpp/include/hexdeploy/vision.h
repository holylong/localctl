// Copyright (c) 2022 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
#pragma once

#include "hexdeploy/core/config.h"
#ifdef ENABLE_VISION
#include "hexdeploy/vision/classification/contrib/resnet.h"
#include "hexdeploy/vision/classification/contrib/yolov5cls/yolov5cls.h"
#include "hexdeploy/vision/classification/ppcls/model.h"
#include "hexdeploy/vision/classification/ppshitu/ppshituv2_rec.h"
#include "hexdeploy/vision/classification/ppshitu/ppshituv2_det.h"
#include "hexdeploy/vision/detection/contrib/nanodet_plus.h"
#include "hexdeploy/vision/detection/contrib/scaledyolov4.h"
#include "hexdeploy/vision/detection/contrib/yolor.h"
#include "hexdeploy/vision/detection/contrib/yolov5/yolov5.h"
#include "hexdeploy/vision/detection/contrib/yolov5seg/yolov5seg.h"
#include "hexdeploy/vision/detection/contrib/fastestdet/fastestdet.h"
#include "hexdeploy/vision/detection/contrib/yolov5lite.h"
#include "hexdeploy/vision/detection/contrib/yolov6.h"
#include "hexdeploy/vision/detection/contrib/yolov7/yolov7.h"
#include "hexdeploy/vision/detection/contrib/yolov7end2end_ort.h"
#include "hexdeploy/vision/detection/contrib/yolov7end2end_trt.h"
#include "hexdeploy/vision/detection/contrib/yolov8/yolov8.h"
#include "hexdeploy/vision/detection/contrib/yolox.h"
#include "hexdeploy/vision/detection/contrib/rknpu2/model.h"
#include "hexdeploy/vision/perception/paddle3d/smoke/smoke.h"
#include "hexdeploy/vision/perception/paddle3d/petr/petr.h"
#include "hexdeploy/vision/perception/paddle3d/centerpoint/centerpoint.h"
#include "hexdeploy/vision/perception/paddle3d/caddn/caddn.h"
#include "hexdeploy/vision/detection/ppdet/model.h"
#include "hexdeploy/vision/facealign/contrib/face_landmark_1000.h"
#include "hexdeploy/vision/facealign/contrib/pfld.h"
#include "hexdeploy/vision/facealign/contrib/pipnet.h"
#include "hexdeploy/vision/facedet/contrib/retinaface.h"
#include "hexdeploy/vision/facedet/contrib/scrfd.h"
#include "hexdeploy/vision/facedet/contrib/ultraface.h"
#include "hexdeploy/vision/facedet/contrib/yolov5face.h"
#include "hexdeploy/vision/facedet/contrib/yolov7face/yolov7face.h"
#include "hexdeploy/vision/facedet/contrib/centerface/centerface.h"
#include "hexdeploy/vision/facedet/ppdet/blazeface/blazeface.h"
#include "hexdeploy/vision/faceid/contrib/insightface/model.h"
#include "hexdeploy/vision/faceid/contrib/adaface/adaface.h"
#include "hexdeploy/vision/headpose/contrib/fsanet.h"
#include "hexdeploy/vision/keypointdet/pptinypose/pptinypose.h"
#include "hexdeploy/vision/matting/contrib/modnet.h"
#include "hexdeploy/vision/matting/contrib/rvm.h"
#include "hexdeploy/vision/matting/ppmatting/ppmatting.h"
#include "hexdeploy/vision/ocr/ppocr/classifier.h"
#include "hexdeploy/vision/ocr/ppocr/dbdetector.h"
#include "hexdeploy/vision/ocr/ppocr/structurev2_table.h"
#include "hexdeploy/vision/ocr/ppocr/structurev2_layout.h"
#include "hexdeploy/vision/ocr/ppocr/structurev2_ser_vi_layoutxlm.h"
#include "hexdeploy/vision/ocr/ppocr/ppocr_v2.h"
#include "hexdeploy/vision/ocr/ppocr/ppocr_v3.h"
#include "hexdeploy/vision/ocr/ppocr/ppocr_v4.h"
#include "hexdeploy/vision/ocr/ppocr/ppstructurev2_table.h"
#include "hexdeploy/vision/ocr/ppocr/ppstructurev2_layout.h"
#include "hexdeploy/vision/ocr/ppocr/recognizer.h"
#include "hexdeploy/vision/ocr/ppocr/utils/ocr_utils.h"
#include "hexdeploy/vision/segmentation/ppseg/model.h"
#include "hexdeploy/vision/sr/ppsr/model.h"
#include "hexdeploy/vision/tracking/pptracking/model.h"
#include "hexdeploy/vision/generation/contrib/animegan.h"

#endif

#include "hexdeploy/vision/visualize/visualize.h"
