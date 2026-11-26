cmake_minimum_required(VERSION 3.22)
project(upscaler)

# ncnn prebuilt for Android with Vulkan (Tencent/ncnn release 20261020, arm64-v8a only).
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(upscaler SHARED upscaler.cpp)
target_link_libraries(upscaler ncnn android jnigraphics log)
