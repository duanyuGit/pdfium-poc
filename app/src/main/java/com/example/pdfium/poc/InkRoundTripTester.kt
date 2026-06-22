package com.example.pdfium.poc

import android.util.Log
import java.io.File

/**
 * Plan 2: Ink round-trip 命门验证 (PDFium native 自家)
 *
 * 工作流:
 *  1. 复制 src PDF 到 out 临时文件
 *  2. 用 PDFium native 打开 out
 *  3. nativeAddInk 写 5 个 stroke 到 page 0
 *  4. nativeSaveAsCopy 保存
 *  5. 重新打开 out PDF
 *  6. nativeInspectAnnotations 验证: annotCount = 5, 全部 subtype = FPDF_ANNOT_INK (15)
 *  7. 输出 logcat + 返回 verdict
 *
 * 跨 reader (Acrobat / Foxit) 验证: 自动测不到, 把 out PDF pull 出去用户人工开
 */
class InkRoundTripTester(private val srcPdf: File, private val outPdf: File) {

    data class Stroke(val points: FloatArray, val colorArgb: Int, val widthPt: Float)

    data class Verdict(
        val pass: Boolean,
        val annotCount: Int,
        val inkAnnotCount: Int,
        val inspectionResult: String
    )

    fun run(): Verdict {
        // 1. 准备 stroke 数据
        val strokes = makeTestStrokes()

        // 2. 复制源文件
        srcPdf.copyTo(outPdf, overwrite = true)
        Log.i(TAG, "Copied ${srcPdf.name} → ${outPdf.absolutePath}")

        // 3. 写 ink
        val doc1 = PdfiumCore.nativeOpenDocument(outPdf.absolutePath, null)
        if (doc1 == 0L) return Verdict(false, 0, 0, "Cannot open src PDF")

        var allWriteOk = true
        for ((i, stroke) in strokes.withIndex()) {
            val ok = PdfiumCore.nativeAddInk(doc1, 0, stroke.points, stroke.colorArgb, stroke.widthPt)
            Log.i(TAG, "Stroke $i write ok=$ok pts=${stroke.points.size / 2}")
            if (!ok) allWriteOk = false
        }

        // 4. 保存
        val saveOk = PdfiumCore.nativeSaveAsCopy(doc1, outPdf.absolutePath + ".tmp")
        PdfiumCore.nativeCloseDocument(doc1)
        if (!saveOk) return Verdict(false, 0, 0, "nativeSaveAsCopy failed")

        File(outPdf.absolutePath + ".tmp").renameTo(outPdf)
        Log.i(TAG, "Saved to ${outPdf.absolutePath}")

        // 5. 重新打开 + 探测
        val doc2 = PdfiumCore.nativeOpenDocument(outPdf.absolutePath, null)
        if (doc2 == 0L) return Verdict(false, 0, 0, "Cannot reopen saved PDF")

        val inspect = PdfiumCore.nativeInspectAnnotations(doc2, 0)
        Log.i(TAG, "Inspection: $inspect")
        PdfiumCore.nativeCloseDocument(doc2)

        // 解析 inspection result
        val annotCount = Regex("annotCount=(\\d+)").find(inspect)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val inkCount = Regex("inkAnnotCount=(\\d+)").find(inspect)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val pass = allWriteOk && annotCount == 5 && inkCount == 5

        return Verdict(pass, annotCount, inkCount, inspect)
    }

    private fun makeTestStrokes(): List<Stroke> = listOf(
        // 1. 直线
        Stroke(floatArrayOf(100f, 700f, 300f, 700f), 0xFFFFFF00.toInt(), 3f),
        // 2. 加粗蓝线
        Stroke(floatArrayOf(100f, 650f, 300f, 650f), 0xFF0000FF.toInt(), 5f),
        // 3. 弧线 (sin 离散 100 点)
        Stroke(buildSineWave(100, startX = 100f, startY = 600f, amplitudePt = 30f, lengthPt = 200f),
            0xFFFF0000.toInt(), 2f),
        // 4. X 交叉两段
        Stroke(floatArrayOf(100f, 500f, 200f, 540f, 200f, 500f, 100f, 540f),
            0xFF00FF00.toInt(), 4f),
        // 5. 单点 (短一点)
        Stroke(floatArrayOf(200f, 450f, 201f, 451f), 0xFFFF00FF.toInt(), 8f)
    )

    private fun buildSineWave(samples: Int, startX: Float, startY: Float,
                              amplitudePt: Float, lengthPt: Float): FloatArray {
        val arr = FloatArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1)
            arr[i * 2] = startX + t * lengthPt
            arr[i * 2 + 1] = startY + amplitudePt * kotlin.math.sin(t * Math.PI * 4).toFloat()
        }
        return arr
    }

    companion object {
        private const val TAG = "InkRoundTrip"
    }
}
