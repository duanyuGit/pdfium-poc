package com.example.pdfium.poc

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * PoC 主入口
 *
 * Day 1 (Plan 0): 自动打开 test.pdf, 渲染第 1 页, logcat 输出页数 + 首页前 20 char quad
 * Day 2 (Plan 1): 点 [Run CJK Comparison] 按钮触发 CjkQuadComparator 批量对比
 *
 * 真机准备:
 *   adb push test.pdf      /sdcard/Android/data/com.example.pdfium.poc/files/
 *   adb push test_pdfs/.   /sdcard/Android/data/com.example.pdfium.poc/files/test_pdfs/
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "PdfPocMain"
    private lateinit var statusText: TextView
    private lateinit var imageView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = TextView(this).apply {
            text = "pdfium-poc 启动"
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        val runComparisonBtn = Button(this).apply {
            text = "Run CJK Quad Comparison (Plan 1)"
            setOnClickListener { runCjkComparison() }
        }
        val runInkRoundTripBtn = Button(this).apply {
            text = "Run Ink Round-Trip (Plan 2)"
            setOnClickListener { runInkRoundTrip() }
        }
        imageView = ImageView(this)
        val scroll = ScrollView(this).apply { addView(imageView) }
        root.addView(statusText)
        root.addView(runComparisonBtn)
        root.addView(runInkRoundTripBtn)
        root.addView(scroll)
        setContentView(root)

        loadAndRenderTestPdf()
    }

    private fun loadAndRenderTestPdf() {
        val testPdf = File(getExternalFilesDir(null), "test.pdf")
        if (!testPdf.exists()) {
            statusText.text = "缺 test.pdf, 请 adb push 到:\n${testPdf.absolutePath}"
            return
        }

        Thread {
            val doc = PdfiumCore.nativeOpenDocument(testPdf.absolutePath, null)
            if (doc == 0L) {
                runOnUiThread { statusText.text = "无法打开 test.pdf (可能加密或损坏)" }
                return@Thread
            }

            val pageCount = PdfiumCore.nativeGetPageCount(doc)
            val size = PdfiumCore.nativeGetPageSize(doc, 0)
            Log.i(TAG, "Doc=$doc pages=$pageCount page0=(${size[0]}, ${size[1]}) pt")

            val w = 1080
            val h = (1080 * size[1] / size[0]).toInt().coerceAtLeast(100)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            PdfiumCore.nativeRenderPage(doc, 0, bmp, w, h)

            val charQuads = PdfiumCore.getCharQuads(doc, 0)
            Log.i(TAG, "Page 0 extracted ${charQuads.size} chars (PDFium)")
            charQuads.take(10).forEachIndexed { idx, q ->
                Log.i(TAG, "  char[$idx] cp=0x${Integer.toHexString(q.codepoint)} '${q.text}'" +
                        " ul=${q.ul} lr=${q.lr}")
            }

            PdfiumCore.nativeCloseDocument(doc)

            runOnUiThread {
                statusText.text = "✓ test.pdf $pageCount 页, page0 ${charQuads.size} chars\n" +
                        "→ 点上方按钮跑 Plan 1 CJK 对比"
                imageView.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun runInkRoundTrip() {
        val src = File(getExternalFilesDir(null), "test_pdfs/cn_test.pdf")
        if (!src.exists()) {
            statusText.text = "缺源 PDF (cn_test.pdf), 先跑 Plan 1 推过去"
            return
        }
        val out = File(getExternalFilesDir(null), "ink_out.pdf")
        statusText.text = "Plan 2 Ink round-trip 运行中..."
        Thread {
            try {
                val v = InkRoundTripTester(src, out).run()
                val msg = buildString {
                    val emoji = if (v.pass) "🟢" else "🔴"
                    append("$emoji Plan 2 Ink Round-Trip: pass=${v.pass}\n")
                    append("写入 5 stroke → 重读: annotCount=${v.annotCount}, inkAnnotCount=${v.inkAnnotCount}\n\n")
                    append("Inspection 全文:\n${v.inspectionResult}\n\n")
                    append("产物: ${out.absolutePath}\n")
                    append("用 adb pull 拿到本地, 用 Adobe Acrobat / Foxit 打开验证视觉是否可见 + 跨 reader 兼容")
                }
                Log.i(TAG, msg)
                runOnUiThread { statusText.text = msg }
            } catch (e: Exception) {
                Log.e(TAG, "Plan 2 failed", e)
                runOnUiThread { statusText.text = "失败: ${e.message}" }
            }
        }.start()
    }

    private fun runCjkComparison() {
        val testDir = File(getExternalFilesDir(null), "test_pdfs")
        if (!testDir.exists() || (testDir.listFiles()?.size ?: 0) == 0) {
            statusText.text = "缺测试 PDF, 请 adb push 10 份 dogfood PDF 到:\n${testDir.absolutePath}"
            return
        }

        val outDir = File(getExternalFilesDir(null), "reports")
        statusText.text = "Plan 1 对比运行中,见 logcat..."
        Thread {
            try {
                val results = CjkQuadComparator.runBatch(testDir, outDir)
                val summary = buildString {
                    append("✓ Plan 1 完成, ${results.size} 份 PDF\n")
                    append("详细 CSV: ${outDir.absolutePath}/\n\n")
                    results.forEach { (name, r) ->
                        // X-only verdict: 用户感知最关键的维度
                        val verdict = when {
                            r.avgDiffXPt <= 0.5 && r.maxDiffXPt <= 3.0 -> "🟢"
                            r.avgDiffXPt <= 1.5 && r.maxDiffXPt <= 10.0 -> "🟡"
                            else -> "🔴"
                        }
                        append("$verdict $name: X=${"%.2f".format(r.avgDiffXPt)}/${"%.2f".format(r.maxDiffXPt)}pt " +
                                "Y=${"%.2f".format(r.avgDiffYPt)}/${"%.2f".format(r.maxDiffYPt)}pt\n")
                    }
                }
                Log.i(TAG, summary)
                runOnUiThread { statusText.text = summary }
            } catch (e: Exception) {
                Log.e(TAG, "CJK comparison failed", e)
                runOnUiThread { statusText.text = "失败: ${e.message}" }
            }
        }.start()
    }
}
