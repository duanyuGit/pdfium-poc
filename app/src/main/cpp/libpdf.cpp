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
#include "fpdf_annot.h"
#include "fpdf_edit.h"
#include "fpdf_save.h"

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

        // GetCharBox 已经返回 PDF user space 的 AABB (l, r, b, t),
        // 是字符的实际位置 + 大小。直接当 4 corner 用。
        //
        // 之前尝试用 FPDFText_GetMatrix + unit box [0,1]×[0,1] 变换 — 这是错的。
        // GetMatrix 返回 char glyph 的 font transform, 不是 unit box → 字符位置的映射。
        // 旋转字符场景 GetCharBox AABB 会比真实 glyph 包络偏大, 但平面 ASCII / CJK
        // 阅读方向场景, AABB 已经足够精确。
        float ulX = (float)l, ulY = (float)t;
        float urX = (float)r, urY = (float)t;
        float llX = (float)l, llY = (float)b;
        float lrX = (float)r, lrY = (float)b;

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

// ---------- Plan 2: Ink round-trip (PDFium native 自家) ----------

// 写 Ink 标注: points = [x0,y0, x1,y1, ...] PDF 坐标 Y 朝上
// colorArgb = 0xAARRGGBB, strokeWidth in PDF pt
// 关键: FPDFAnnot_SetBorder 必填线宽, 否则 PDFium 以 0 宽渲染不可见 (DocReaderReplica 实测)
JNIEXPORT jboolean JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeAddInk(
        JNIEnv* env, jobject, jlong docPtr, jint pageIndex,
        jfloatArray jpoints, jint colorArgb, jfloat strokeWidth) {
    if (!docPtr) return JNI_FALSE;
    FPDF_DOCUMENT doc = reinterpret_cast<FPDF_DOCUMENT>(docPtr);
    FPDF_PAGE page = FPDF_LoadPage(doc, pageIndex);
    if (!page) return JNI_FALSE;
    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_INK);
    if (!annot) { FPDF_ClosePage(page); return JNI_FALSE; }

    unsigned int A = (static_cast<unsigned int>(colorArgb) >> 24) & 0xFF;
    unsigned int R = (static_cast<unsigned int>(colorArgb) >> 16) & 0xFF;
    unsigned int G = (static_cast<unsigned int>(colorArgb) >> 8) & 0xFF;
    unsigned int B = static_cast<unsigned int>(colorArgb) & 0xFF;
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_Color, R, G, B, A);

    jsize n = env->GetArrayLength(jpoints);
    jfloat* pts = env->GetFloatArrayElements(jpoints, nullptr);
    int pointCount = n / 2;
    jboolean ok = JNI_FALSE;
    if (pointCount >= 2) {
        std::vector<FS_POINTF> fsPts(pointCount);
        float minX = pts[0], maxX = pts[0];
        float minY = pts[1], maxY = pts[1];
        for (int i = 0; i < pointCount; i++) {
            fsPts[i].x = pts[i * 2];
            fsPts[i].y = pts[i * 2 + 1];
            if (pts[i * 2]     < minX) minX = pts[i * 2];
            if (pts[i * 2]     > maxX) maxX = pts[i * 2];
            if (pts[i * 2 + 1] < minY) minY = pts[i * 2 + 1];
            if (pts[i * 2 + 1] > maxY) maxY = pts[i * 2 + 1];
        }
        FPDFAnnot_AddInkStroke(annot, fsPts.data(), pointCount);

        float pad = strokeWidth + 2.0f;
        FS_RECTF rect = { minX - pad, maxY + pad, maxX + pad, minY - pad };
        FPDFAnnot_SetRect(annot, &rect);
        FPDFAnnot_SetBorder(annot, 0.0f, 0.0f, strokeWidth);
        ok = JNI_TRUE;
    }
    env->ReleaseFloatArrayElements(jpoints, pts, JNI_ABORT);
    FPDFPage_CloseAnnot(annot);
    FPDFPage_GenerateContent(page);
    FPDF_ClosePage(page);
    return ok;
}

// 探测某页所有 annotation 的 subtype 和 ink 数据
// 返回 String: "annotCount=N,subtype0=X,subtype1=Y,...,inkCount=M,ink0_paths=P"
JNIEXPORT jstring JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeInspectAnnotations(
        JNIEnv* env, jobject, jlong docPtr, jint pageIndex) {
    if (!docPtr) return env->NewStringUTF("error: null doc");
    FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(docPtr), pageIndex);
    if (!page) return env->NewStringUTF("error: null page");
    int n = FPDFPage_GetAnnotCount(page);
    std::string result = "annotCount=" + std::to_string(n);
    int inkCount = 0;
    for (int i = 0; i < n; i++) {
        FPDF_ANNOTATION annot = FPDFPage_GetAnnot(page, i);
        if (!annot) continue;
        int subtype = FPDFAnnot_GetSubtype(annot);
        result += ",subtype" + std::to_string(i) + "=" + std::to_string(subtype);
        if (subtype == FPDF_ANNOT_INK) {
            int pathCount = FPDFAnnot_GetInkListCount(annot);
            result += ",ink" + std::to_string(inkCount) + "_paths=" + std::to_string(pathCount);
            for (int pi = 0; pi < pathCount; pi++) {
                int ptCount = FPDFAnnot_GetInkListPath(annot, pi, nullptr, 0);
                result += ",ink" + std::to_string(inkCount) + "_path" + std::to_string(pi) + "_pts=" + std::to_string(ptCount);
            }
            inkCount++;
        }
        FPDFPage_CloseAnnot(annot);
    }
    result += ",inkAnnotCount=" + std::to_string(inkCount);
    FPDF_ClosePage(page);
    return env->NewStringUTF(result.c_str());
}

// 用 FPDF_SaveAsCopy 保存到指定路径
struct FileWriteCtx : FPDF_FILEWRITE {
    FILE* fp;
};
static int WriteBlockCallback(FPDF_FILEWRITE* pThis, const void* data, unsigned long size) {
    FileWriteCtx* ctx = static_cast<FileWriteCtx*>(pThis);
    return fwrite(data, 1, size, ctx->fp) == size ? 1 : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_example_pdfium_poc_PdfiumCore_nativeSaveAsCopy(
        JNIEnv* env, jobject, jlong docPtr, jstring joutPath) {
    if (!docPtr) return JNI_FALSE;
    const char* out = env->GetStringUTFChars(joutPath, nullptr);
    FILE* fp = fopen(out, "wb");
    env->ReleaseStringUTFChars(joutPath, out);
    if (!fp) return JNI_FALSE;
    FileWriteCtx ctx;
    ctx.version = 1;
    ctx.WriteBlock = WriteBlockCallback;
    ctx.fp = fp;
    FPDF_BOOL ok = FPDF_SaveAsCopy(reinterpret_cast<FPDF_DOCUMENT>(docPtr), &ctx, 0);
    fclose(fp);
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
