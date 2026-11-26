// Real-ESRGAN (x4plus-anime) upscaling with ncnn, on the GPU through Vulkan when there is one.
// The image is processed in tiles with an overlap so memory stays small and seams don't show.

#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cstring>

#include "net.h"
#include "gpu.h"

#define TAG "DexBackdrop"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const int SCALE = 4;
static const int TILE = 192;   // input pixels per tile side
static const int PAD = 12;     // overlap on each side, cut away after upscaling

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_dexwp_backdrop_engine_Upscaler_nativeUpscale(JNIEnv *env, jobject, jobject assets, jobject in, jobject out, jobject progress) {
    AndroidBitmapInfo ii, oi;
    if (AndroidBitmap_getInfo(env, in, &ii) < 0 || AndroidBitmap_getInfo(env, out, &oi) < 0) return JNI_FALSE;
    if (ii.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || oi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (oi.width != ii.width * SCALE || oi.height != ii.height * SCALE) return JNI_FALSE;

    jclass pc = env->GetObjectClass(progress);
    jmethodID onTile = env->GetMethodID(pc, "onTile", "(II)Z");

    ncnn::create_gpu_instance();
    bool gpu = ncnn::get_gpu_count() > 0;
    bool ok = true;
    {
        ncnn::Net net;
        net.opt.use_vulkan_compute = gpu;
        net.opt.use_fp16_packed = true;
        net.opt.use_fp16_storage = true;
        net.opt.use_fp16_arithmetic = false;
        net.opt.num_threads = 4;
        AAssetManager *mgr = AAssetManager_fromJava(env, assets);
        if (net.load_param(mgr, "esrgan/realesrgan-x4plus-anime.param") != 0 ||
            net.load_model(mgr, "esrgan/realesrgan-x4plus-anime.bin") != 0) {
            LOGW("upscaler: model failed to load");
            ok = false;
        }
        LOGI("upscaler: %ux%u on %s", ii.width, ii.height, gpu ? "GPU" : "CPU");

        void *ip = nullptr, *op = nullptr;
        if (ok && (AndroidBitmap_lockPixels(env, in, &ip) < 0 || AndroidBitmap_lockPixels(env, out, &op) < 0)) ok = false;
        const int W = ii.width, H = ii.height;
        const int tx = (W + TILE - 1) / TILE, ty = (H + TILE - 1) / TILE, total = tx * ty;
        const float norm_in[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        const float norm_out[3] = {255.f, 255.f, 255.f};
        int done = 0;
        for (int y = 0; ok && y < ty; y++) {
            for (int x = 0; ok && x < tx; x++) {
                // Tile with overlap, clamped to the image.
                int x0 = x * TILE, y0 = y * TILE;
                int x1 = std::min(x0 + TILE, W), y1 = std::min(y0 + TILE, H);
                int px0 = std::max(x0 - PAD, 0), py0 = std::max(y0 - PAD, 0);
                int px1 = std::min(x1 + PAD, W), py1 = std::min(y1 + PAD, H);
                ncnn::Mat m = ncnn::Mat::from_pixels_roi((const unsigned char *) ip, ncnn::Mat::PIXEL_RGBA2RGB, W, H, ii.stride,
                                                         px0, py0, px1 - px0, py1 - py0);
                m.substract_mean_normalize(nullptr, norm_in);
                ncnn::Mat r;
                {
                    ncnn::Extractor ex = net.create_extractor();
                    ex.input("data", m);
                    if (ex.extract("output", r) != 0) { ok = false; break; }
                }
                r.substract_mean_normalize(nullptr, norm_out);
                // Back to RGBA, then copy the tile's own area (without the overlap) into place.
                int rw = r.w, rh = r.h;
                unsigned char *rgba = new unsigned char[rw * rh * 4];
                r.to_pixels(rgba, ncnn::Mat::PIXEL_RGB2RGBA);
                int ox = (x0 - px0) * SCALE, oy = (y0 - py0) * SCALE;
                int cw = (x1 - x0) * SCALE, ch = (y1 - y0) * SCALE;
                for (int row = 0; row < ch; row++) {
                    unsigned char *dst = (unsigned char *) op + (size_t) (y0 * SCALE + row) * oi.stride + (size_t) x0 * SCALE * 4;
                    const unsigned char *src = rgba + ((size_t) (oy + row) * rw + ox) * 4;
                    memcpy(dst, src, (size_t) cw * 4);
                }
                delete[] rgba;
                done++;
                if (!env->CallBooleanMethod(progress, onTile, done, total)) ok = false;
            }
        }
        if (ip) AndroidBitmap_unlockPixels(env, in);
        if (op) AndroidBitmap_unlockPixels(env, out);
    }
    ncnn::destroy_gpu_instance();
    return ok ? JNI_TRUE : JNI_FALSE;
}
