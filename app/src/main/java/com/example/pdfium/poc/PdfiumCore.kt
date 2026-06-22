package com.example.pdfium.poc

import android.graphics.Bitmap

/**
 * pdfium-poc PDFium 引擎封装
 *
 * 加载顺序:
 *  1. libpdfium.so       — bblanchon/pdfium-binaries 预编译(BSD, 16KB 对齐)
 *  2. libpdfpoc.so       — 项目自有 JNI 桥 (CMake 编译 libpdf.cpp)
 *
 * Day 1: 基础生命周期 + 渲染
 * Day 2: char quad 提取(Plan 1 命门验证)
 */
object PdfiumCore {

    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("pdfpoc")
        nativeInit()
    }

    // ---------- 基础生命周期 ----------

    private external fun nativeInit()

    external fun nativeOpenDocument(path: String, password: String?): Long

    external fun nativeGetPageCount(docPtr: Long): Int

    /** 返回 [width, height], 单位点(72 dpi) */
    external fun nativeGetPageSize(docPtr: Long, pageIndex: Int): FloatArray

    external fun nativeRenderPage(
        docPtr: Long,
        pageIndex: Int,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Boolean

    external fun nativeCloseDocument(docPtr: Long)

    // ---------- Plan 1: CJK char-level quad ----------

    // ---------- Plan 2: Ink round-trip ----------

    /**
     * 写 Ink 标注 (PDFium native FPDFAnnot_AddInkStroke + SetBorder)
     * @param points [x0,y0, x1,y1, ...] PDF 坐标 Y 朝上
     * @param colorArgb 0xAARRGGBB
     * @param strokeWidth in PDF pt (必填, 否则不可见)
     */
    external fun nativeAddInk(
        docPtr: Long, pageIndex: Int,
        points: FloatArray, colorArgb: Int, strokeWidth: Float
    ): Boolean

    /** 探测某页 annotation 元数据, 返回结构化字符串 */
    external fun nativeInspectAnnotations(docPtr: Long, pageIndex: Int): String

    /** FPDF_SaveAsCopy 保存到指定路径 (flag=0 全量保存) */
    external fun nativeSaveAsCopy(docPtr: Long, outPath: String): Boolean

    /**
     * 提取一页所有字符的 quad 4 角点 + codepoint。
     *
     * 返回 FloatArray, 每 9 个为一组:
     *   [0..7] = (ulX, ulY, urX, urY, llX, llY, lrX, lrY), PDF 坐标 Y 朝上
     *   [8]    = codepoint (Float cast Int)
     *
     * 跳过 codepoint == 0 (控制字符 / CID-only)。
     */
    external fun nativeGetPageCharQuads(docPtr: Long, pageIndex: Int): FloatArray

    // ---------- Kotlin 便捷 API ----------

    data class CharQuad(
        val codepoint: Int,
        val text: String,
        val ul: Pair<Float, Float>,
        val ur: Pair<Float, Float>,
        val ll: Pair<Float, Float>,
        val lr: Pair<Float, Float>,
    )

    /** 解包 nativeGetPageCharQuads 返回值 */
    fun getCharQuads(docPtr: Long, pageIndex: Int): List<CharQuad> {
        val raw = nativeGetPageCharQuads(docPtr, pageIndex)
        val n = raw.size / 9
        return List(n) { k ->
            val off = k * 9
            val cp = raw[off + 8].toInt()
            CharQuad(
                codepoint = cp,
                text = String(Character.toChars(cp)),
                ul = raw[off + 0] to raw[off + 1],
                ur = raw[off + 2] to raw[off + 3],
                ll = raw[off + 4] to raw[off + 5],
                lr = raw[off + 6] to raw[off + 7],
            )
        }
    }
}
