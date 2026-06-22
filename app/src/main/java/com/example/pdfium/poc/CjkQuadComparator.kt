package com.example.pdfium.poc

import android.util.Log
import com.artifex.mupdf.fitz.Document
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Plan 1 命门验证: 对比 MuPDF char.quad 与 PDFium 自合成 quad 的精度差异
 *
 * 工作流:
 *  1. 同一 PDF 文件用两个引擎分别提取 char quad
 *  2. 按 (page, codepoint, 出现序号) 配对
 *  3. 计算每个 char 4 角点的欧氏距离(px @ 渲染像素空间)
 *  4. 输出 CSV + 统计聚合
 *
 * 坐标对齐:
 *   - MuPDF: top-down (Y 朝下), 原始坐标 = PDF 渲染像素空间
 *   - PDFium: bottom-up (Y 朝上), 原始坐标 = PDF user space (72 dpi)
 *   - 对齐策略: 都映射到 PDF user space(bottom-up), MuPDF Y 翻转 + scale
 *
 * 局限:
 *   - 同一 codepoint 多次出现按"出现顺序"配对, 假设两引擎顺序一致
 *     (大多数 PDF 阅读顺序一致, Bidi/CJK 竖排可能错位 — Plan 1 红队已警告)
 *   - 连字 fi/fl 在 MuPDF 拆 N 个 char + 共享 box, PDFium 同行为 — 配对仍生效
 */
class CjkQuadComparator(
    private val pdfPath: String,
    private val outCsvPath: String
) {

    data class CharQuad(
        val page: Int,
        val codepoint: Int,
        val text: String,
        val ulX: Float, val ulY: Float,
        val urX: Float, val urY: Float,
        val llX: Float, val llY: Float,
        val lrX: Float, val lrY: Float
    )

    data class PageDimensions(val widthPt: Float, val heightPt: Float)

    /**
     * MuPDF char.quad 提取
     * MuPDF 用 top-down 坐标(Y 朝下),与 Android UI 一致
     * 这里翻转到 bottom-up 与 PDFium 对齐
     */
    fun extractWithMuPdf(): Pair<List<CharQuad>, Map<Int, PageDimensions>> {
        val doc = Document.openDocument(pdfPath)
        val result = mutableListOf<CharQuad>()
        val pageDims = mutableMapOf<Int, PageDimensions>()

        for (pageIdx in 0 until doc.countPages()) {
            val page = doc.loadPage(pageIdx)
            val bounds = page.bounds
            val pageHeight = bounds.y1 - bounds.y0
            pageDims[pageIdx] = PageDimensions(bounds.x1 - bounds.x0, pageHeight)

            val sText = page.toStructuredText()
            try {
                sText.blocks?.forEach { block ->
                    block.lines?.forEach { line ->
                        line.chars?.forEach { ch ->
                            val cp = ch.c
                            if (cp <= 0) return@forEach
                            // MuPDF Y 朝下 → 翻转到 PDF bottom-up
                            // PDFium: y_pdf = pageHeight - y_mupdf
                            result.add(CharQuad(
                                page = pageIdx,
                                codepoint = cp,
                                text = String(Character.toChars(cp)),
                                ulX = ch.quad.ul_x, ulY = pageHeight - ch.quad.ul_y,
                                urX = ch.quad.ur_x, urY = pageHeight - ch.quad.ur_y,
                                llX = ch.quad.ll_x, llY = pageHeight - ch.quad.ll_y,
                                lrX = ch.quad.lr_x, lrY = pageHeight - ch.quad.lr_y
                            ))
                        }
                    }
                }
            } finally {
                sText.destroy()
                page.destroy()
            }
        }
        doc.destroy()
        return result to pageDims
    }

    /**
     * PDFium 自合成 quad 提取 (原生 bottom-up,无需翻转)
     */
    fun extractWithPdfium(): List<CharQuad> {
        val doc = PdfiumCore.nativeOpenDocument(pdfPath, null)
        if (doc == 0L) return emptyList()
        val result = mutableListOf<CharQuad>()
        try {
            val pageCount = PdfiumCore.nativeGetPageCount(doc)
            for (pageIdx in 0 until pageCount) {
                val charQuads = PdfiumCore.getCharQuads(doc, pageIdx)
                for (q in charQuads) {
                    result.add(CharQuad(
                        page = pageIdx,
                        codepoint = q.codepoint,
                        text = q.text,
                        ulX = q.ul.first, ulY = q.ul.second,
                        urX = q.ur.first, urY = q.ur.second,
                        llX = q.ll.first, llY = q.ll.second,
                        lrX = q.lr.first, lrY = q.lr.second
                    ))
                }
            }
        } finally {
            PdfiumCore.nativeCloseDocument(doc)
        }
        return result
    }

    data class ComparisonResult(
        val totalPairs: Int,
        val avgDiffPt: Double,
        val maxDiffPt: Double,
        val misalignCount: Int,  // 偏移 > 10pt 视为完全错位
        val misalignRate: Double,
        val perPdfMaxDiff: Double
    )

    /**
     * 跑完整对比, 输出 CSV 并返回统计
     *
     * @param renderDpi 渲染分辨率(默认 144 dpi = 2x scale @ standard 72 dpi)
     *                  在此分辨率下计算像素偏移, 作为人眼可感知阈值
     */
    fun compare(renderDpi: Int = 144): ComparisonResult {
        val (mupdfQuads, pageDims) = extractWithMuPdf()
        val pdfiumQuads = extractWithPdfium()

        // 按 (page, codepoint) 分组, 同 codepoint 多次出现按顺序配对
        val mupdfBuckets = mupdfQuads.groupBy { it.page to it.codepoint }
        val pdfiumBuckets = pdfiumQuads.groupBy { it.page to it.codepoint }

        val pointToPxScale = renderDpi / 72.0  // 1 PDF pt = pointToPxScale 渲染像素
        val pxMisalignThreshold = 10.0  // 10 像素以上视为完全错位
        val ptMisalignThreshold = pxMisalignThreshold / pointToPxScale

        val pageDimsForLog = pageDims

        FileWriter(outCsvPath).use { csv ->
            csv.write("page,codepoint,char,occurrence,mupdf_ulX,mupdf_ulY,pdfium_ulX,pdfium_ulY,ul_diff_pt,ur_diff_pt,ll_diff_pt,lr_diff_pt,max_diff_pt,max_diff_px\n")

            var totalPairs = 0
            var sumDiffPt = 0.0
            var maxDiffPt = 0.0
            var misalignCount = 0

            // 联合所有 key
            val allKeys = (mupdfBuckets.keys + pdfiumBuckets.keys).distinct()
            for (key in allKeys) {
                val mList = mupdfBuckets[key].orEmpty()
                val pList = pdfiumBuckets[key].orEmpty()
                val n = minOf(mList.size, pList.size)
                for (i in 0 until n) {
                    val m = mList[i]
                    val p = pList[i]
                    val ulD = hypot((m.ulX - p.ulX).toDouble(), (m.ulY - p.ulY).toDouble())
                    val urD = hypot((m.urX - p.urX).toDouble(), (m.urY - p.urY).toDouble())
                    val llD = hypot((m.llX - p.llX).toDouble(), (m.llY - p.llY).toDouble())
                    val lrD = hypot((m.lrX - p.lrX).toDouble(), (m.lrY - p.lrY).toDouble())
                    val maxQuadDiff = max(max(ulD, urD), max(llD, lrD))
                    val maxQuadDiffPx = maxQuadDiff * pointToPxScale

                    totalPairs++
                    sumDiffPt += maxQuadDiff
                    if (maxQuadDiff > maxDiffPt) maxDiffPt = maxQuadDiff
                    if (maxQuadDiff > ptMisalignThreshold) misalignCount++

                    csv.write("${m.page},${m.codepoint},${escapeCsv(m.text)},$i," +
                            "${m.ulX},${m.ulY},${p.ulX},${p.ulY}," +
                            "$ulD,$urD,$llD,$lrD,$maxQuadDiff,$maxQuadDiffPx\n")
                }
                // 记录未配对(粒度不同)
                if (mList.size != pList.size) {
                    Log.w(TAG, "Pairing mismatch: codepoint=0x${Integer.toHexString(key.second)} '${
                        String(Character.toChars(key.second))}' mupdf=${mList.size} pdfium=${pList.size}")
                }
            }

            val avg = if (totalPairs > 0) sumDiffPt / totalPairs else 0.0
            return ComparisonResult(
                totalPairs = totalPairs,
                avgDiffPt = avg,
                maxDiffPt = maxDiffPt,
                misalignCount = misalignCount,
                misalignRate = if (totalPairs > 0) misalignCount.toDouble() / totalPairs else 0.0,
                perPdfMaxDiff = maxDiffPt
            )
        }
    }

    private fun escapeCsv(s: String): String {
        // 简易 CSV 转义: 包逗号/引号的用引号包裹 + 内部引号 double
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    companion object {
        private const val TAG = "CjkQuadComparator"

        /** 跑 10 份 PDF 全套对比, 输出主报告到指定目录 */
        fun runBatch(testPdfsDir: File, outputDir: File): List<Pair<String, ComparisonResult>> {
            outputDir.mkdirs()
            val results = mutableListOf<Pair<String, ComparisonResult>>()
            testPdfsDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach { pdf ->
                    val csvOut = File(outputDir, "${pdf.nameWithoutExtension}_diff.csv")
                    try {
                        val cmp = CjkQuadComparator(pdf.absolutePath, csvOut.absolutePath)
                        val r = cmp.compare()
                        Log.i(TAG, "✓ ${pdf.name}: pairs=${r.totalPairs} avgDiff=${"%.3f".format(r.avgDiffPt)}pt " +
                                "maxDiff=${"%.3f".format(r.maxDiffPt)}pt misalign=${r.misalignCount}/${r.totalPairs}")
                        results.add(pdf.name to r)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to compare ${pdf.name}", e)
                    }
                }

            // 写汇总
            File(outputDir, "summary.csv").writeText(buildString {
                append("pdf_name,total_pairs,avg_diff_pt,max_diff_pt,misalign_count,misalign_rate,verdict\n")
                results.forEach { (name, r) ->
                    val verdict = when {
                        r.avgDiffPt <= 0.5 && r.misalignCount == 0 -> "GREEN"
                        r.avgDiffPt <= 1.5 && r.misalignRate < 0.01 -> "YELLOW"
                        else -> "RED"
                    }
                    append("$name,${r.totalPairs},${r.avgDiffPt},${r.maxDiffPt},${r.misalignCount},${r.misalignRate},$verdict\n")
                }
            })
            return results
        }
    }
}
