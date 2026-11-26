# Third-party notices

Deskmat includes the following third-party software and data.

## ncnn

- Files: `DexBackdrop/app/src/main/cpp/ncnn/`, `DexBackdrop/app/src/main/jniLibs/arm64-v8a/libncnn.so`
- Source: https://github.com/Tencent/ncnn
- License: BSD 3-Clause

```
Tencent is pleased to support the open source community by making ncnn available.

Copyright (C) 2017 THL A29 Limited, a Tencent company. All rights reserved.

Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
in compliance with the License. You may obtain a copy of the License at

https://opensource.org/licenses/BSD-3-Clause

Unless required by applicable law or agreed to in writing, software distributed
under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the License for the
specific language governing permissions and limitations under the License.
```

ncnn's own distribution also includes components under other permissive licenses. See its
`LICENSE.txt` for the full list.

## Real-ESRGAN (x4plus-anime model)

- Files: `DexBackdrop/app/src/main/assets/esrgan/realesrgan-x4plus-anime.{bin,param}`
- Source: https://github.com/xinntao/Real-ESRGAN (ncnn model from https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan)
- License: BSD 3-Clause

```
BSD 3-Clause License

Copyright (c) 2021, Xintao Wang
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## AndroidX, Jetpack Compose, Google Play services and MaterialKolor

These are pulled in by Gradle at build time and bundled in the APK under their own licenses
(Apache 2.0, plus the Android Software Development Kit License for Play services).
