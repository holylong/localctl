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

#include "hexdeploy/vision/common/processors/cast.h"
#include "hexdeploy/vision/common/processors/center_crop.h"
#include "hexdeploy/vision/common/processors/color_space_convert.h"
#include "hexdeploy/vision/common/processors/convert.h"
#include "hexdeploy/vision/common/processors/convert_and_permute.h"
#include "hexdeploy/vision/common/processors/crop.h"
#include "hexdeploy/vision/common/processors/hwc2chw.h"
#include "hexdeploy/vision/common/processors/limit_by_stride.h"
#include "hexdeploy/vision/common/processors/limit_short.h"
#include "hexdeploy/vision/common/processors/normalize.h"
#include "hexdeploy/vision/common/processors/normalize_and_permute.h"
#include "hexdeploy/vision/common/processors/pad.h"
#include "hexdeploy/vision/common/processors/pad_to_size.h"
#include "hexdeploy/vision/common/processors/resize.h"
#include "hexdeploy/vision/common/processors/resize_by_short.h"
#include "hexdeploy/vision/common/processors/stride_pad.h"
#include "hexdeploy/vision/common/processors/warp_affine.h"
#include <unordered_set>

namespace hexdeploy {
namespace vision {

void FuseTransforms(std::vector<std::shared_ptr<Processor>>* processors);
// Fuse Normalize + Cast(Float) to Normalize
void FuseNormalizeCast(std::vector<std::shared_ptr<Processor>>* processors);
// Fuse Normalize + HWC2CHW to NormalizeAndPermute
void FuseNormalizeHWC2CHW(std::vector<std::shared_ptr<Processor>>* processors);
// Fuse Normalize + Color Convert
void FuseNormalizeColorConvert(
    std::vector<std::shared_ptr<Processor>>* processors);

}  // namespace vision
}  // namespace hexdeploy
