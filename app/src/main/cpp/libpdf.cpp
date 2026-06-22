// pdfium-poc JNI bridge
// Day 1 (Plan 0): 最小版,验证 init + open + getPageCount + renderPage
// Day 2 (Plan 1): 扩展 nativeGetPageCharQuads 用于 CJK quad 精度对比
//
// 包名: com.example.pdfium.poc.PdfiumCore
// JNI 函数命名规则: Java_com_example_pdfium_poc_PdfiumCore_<methodName>
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <vector>
#include "fpdfview.h"
#include "fpdf_text.h"

#define LOG_TAG "PdfPoc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ---------- 基础生命周期 ----------

JNIEXPORT void JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeInit(JNIEnv*, jobject) {
    FPDF_LIBRARY_CONFIG config;
    memset(&config, 0, sizeof(config));
    config.version = 2;
    FPDF_InitLibraryWithConfig(&config);
    LOGI("PDFium library initialized");
}

JNIEXPORT jlong JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeOpenDocument(
        JNIEnv* env, jobject, jstring jpath, jstring jpassword) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    const char* pwd = jpassword ? env->GetStringUTFChars(jpassword, nullptr) : nullptr;
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, pwd);
    if (!doc) {
        unsigned long err = FPDF_GetLastError();
        LOGE("FPDF_LoadDocument failed path=%s err=%lu", path, err);
    } else {
        LOGI("Opened doc=%p path=%s", doc, path);
    }
    env->ReleaseStringUTFChars(jpath, path);
    if (jpassword) env->ReleaseStringUTFChars(jpassword, pwd);
    return reinterpret_cast<jlong>(doc);
}

JNIEXPORT jint JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeGetPageCount(
        JNIEnv*, jobject, jlong docPtr) {
    if (!docPtr) return 0;
    return FPDF_GetPageCount(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeGetPageSize(
        JNIEnv* env, jobject, jlong docPtr, jint pageIndex) {
    jfloatArray result = env->NewFloatArray(2);
    if (!docPtr) return result;
    FS_SIZEF size;
    if (FPDF_GetPageSizeByIndexF(reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex, &size)) {
        jfloat vals[2] = { size.width, size.height };
        env->SetFloatArrayRegion(result, 0, 2, vals);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeRenderPage(
        JNIEnv* env, jobject, jlong docPtr, jint pageIndex,
        jobject jbitmap, jint width, jint height) {
    if (!docPtr) return JNI_FALSE;
    FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex);
    if (!page) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, jbitmap, &pixels) < 0) {
        FPDF_ClosePage(page);
        return JNI_FALSE;
    }
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, pixels, width * 4);
    FPDFBitmap_FillRect(bmp, 0, 0, width, height, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, page, 0, 0, width, height, 0, FPDF_ANNOT);
    FPDFBitmap_Destroy(bmp);
    AndroidBitmap_unlockPixels(env, jbitmap);
    FPDF_ClosePage(page);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeCloseDocument(
        JNIEnv*, jobject, jlong docPtr) {
    if (docPtr) FPDF_CloseDocument(reinterpret_cast<FPDF_DOCUMENT>(docPtr));
}

// ---------- Plan 1: CJK char-level quad 提取 ----------

// 返回 FloatArray, 每 9 个 = 1 char:
//   [0..7] = quad 4 角点 (ulX, ulY, urX, urY, llX, llY, lrX, lrY), PDF 坐标 Y 朝上
//   [8]    = codepoint (cast to float, BMP 字符够用; 代理对取低位)
//
// 策略选择:
//   优先用 FPDFText_GetMatrix 取得字符 matrix, 拿 unit box 4 角变换得到精确 quad
//   matrix 不可用时退化为 FPDFText_GetCharBox AABB(无旋转/倾斜场景等效)
//
// 跳过逻辑:
//   - codepoint == 0 (控制字符 / CID-only ToUnicode 缺失) → 静默跳过
//   - GetCharBox 失败 → 静默跳过
//
// 已知坑(Plan 1 红队):
//   - 连字 (fi/fl): 多个 char index 共享同一 glyph box, quad 会重叠
//   - Bidi: 字符顺序 ≠ visual 顺序, 需要上层重排
//   - 旋转字符: 必须用 matrix 策略, AABB 策略会包络偏大
JNIEXPORT jfloatArray JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeGetPageCharQuads(
        JNIEnv* env, jobject, jlong docPtr, jint pageIndex) {
    if (!docPtr) return env->NewFloatArray(0);
    FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex);
    if (!page) return env->NewFloatArray(0);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(page);
    if (!tp) { FPDF_ClosePage(page); return env->NewFloatArray(0); }

    int n = FPDFText_CountChars(tp);
    std::vector<float> out;
    out.reserve(n * 9);

    for (int i = 0; i < n; i++) {
        unsigned int cp = FPDFText_GetUnicode(tp, i);
        if (cp == 0) continue;  // 跳过控制字符 / CID-only

        double l, r, b, t;
        if (!FPDFText_GetCharBox(tp, i, &l, &r, &b, &t)) continue;

        // 尝试取 matrix
        FS_MATRIX m = {1, 0, 0, 1, 0, 0};
        bool hasMatrix = FPDFText_GetMatrix(tp, i, &m);

        float ulX, ulY, urX, urY, llX, llY, lrX, lrY;

        if (hasMatrix) {
            // matrix 策略: unit box [0,0]-[1,1] 经 matrix 变换得到 quad
            // PDF matrix: [a b 0; c d 0; e f 1]: point (x,y) → (a*x + c*y + e, b*x + d*y + f)
            // unit box: (0,0)=LL, (1,0)=LR, (0,1)=UL, (1,1)=UR
            auto apply = [&m](float x, float y) -> std::pair<float, float> {
                return {m.a * x + m.c * y + m.e, m.b * x + m.d * y + m.f};
            };
            auto ll = apply(0, 0);
            auto lr = apply(1, 0);
            auto ul = apply(0, 1);
            auto ur = apply(1, 1);
            ulX = ul.first;  ulY = ul.second;
            urX = ur.first;  urY = ur.second;
            llX = ll.first;  llY = ll.second;
            lrX = lr.first;  lrY = lr.second;
        } else {
            // AABB 策略: 直接用 GetCharBox 边界
            ulX = (float)l; ulY = (float)t;
            urX = (float)r; urY = (float)t;
            llX = (float)l; llY = (float)b;
            lrX = (float)r; lrY = (float)b;
        }

        out.push_back(ulX); out.push_back(ulY);
        out.push_back(urX); out.push_back(urY);
        out.push_back(llX); out.push_back(llY);
        out.push_back(lrX); out.push_back(lrY);
        out.push_back((float)cp);
    }

    FPDFText_ClosePage(tp);
    FPDF_ClosePage(page);

    jfloatArray result = env->NewFloatArray(out.size());
    env->SetFloatArrayRegion(result, 0, out.size(), out.data());
    return result;
}

}  // extern "C"
