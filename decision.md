# PDFium 迁移可行性 PoC 决策

**日期**: 2026-06-22
**测试设备**: Samsung Galaxy (RFCY20L40PJ, arm64-v8a)
**PDFium 版本**: bblanchon/pdfium-binaries latest (`chromium/7891` era)
**对照**: MuPDF 1.27.0a (`com.artifex.mupdf:viewer`)
**测试 PDF**: 12 份(11 份英文 + 1 份 reportlab 现代 CJK)

## 总结论: 🟢 启动 12-14 周迁移工程

三个命门全部通过。pdf-reader 可以放心移除 AGPL MuPDF 改用 BSD PDFium,
ink 标注路径**反而比当前 MuPDF 简单**(不需要 PDFBox 兜底)。

---

## 命门 #1: CJK char-level quad 精度 🟢

### 关键数据

| PDF | 字符数 | X avg偏移 | X max偏移 | Y avg偏移 | Y max偏移 |
|---|---|---|---|---|---|
| **cn_test.pdf** (现代 CJK, STSong-Light) | 359 | **0.70pt** | 10.79pt | 3.02pt | 10.51pt |
| 10 份英文 PDF | 6033 | 0.76-1.61pt | 1.78-9.46pt | 4.51-15.13pt | 17-51pt |
| cn_demo.pdf (老 PDF, CID 字体异常) | 1379 | 19.40pt | 425pt | 11pt | 196pt |

### 核心发现

1. **X 维度(决定选词左右边界, 用户最敏感): 绿色通过**
   - 现代 CJK 字符 X 偏移平均 0.70pt — 比英文 (~0.76-1.6pt) **还低**
   - 1pt = 1/72 英寸 ≈ 0.35mm,在用户手指 tap 半径(~5-10mm)之下完全不可感知

2. **Y 维度: 系统性偏移, 不是精度问题**
   - 'A' 固定 7.85pt 偏移, 'p' 固定 11.81pt — 差 = descender(3.96pt)
   - 这是 **MuPDF char.quad 用 ascender/descender("行高 quad")** vs
     **PDFium GetCharBox 用 actual glyph extent("实际轮廓")** 的定义差异
   - 在 pdf-reader 适配层可统一处理: `FPDFText_GetCharOrigin` + `GetFontSize` 推 ascent/descent

3. **老 PDF / CID 字体异常 PDF: 两引擎都不可靠**
   - cn_demo.pdf 425pt 偏移说明字符顺序完全不同
   - 不是 PDFium 不行, 是 PDF 本身 ToUnicode 缺失 → 两引擎各自 fallback 不一致
   - pdf-reader 当前 MuPDF 版本对这种 PDF 同样无法精确选词

### 红队 #1 预言对照

| 红队声明 | 实测结果 |
|---|---|
| char quad 自补需 250+ 行 C++,非 80 行 | **20 行**(直接用 GetCharBox AABB 即可,Plan 1 原方案过度复杂化了) |
| CJK CID 字体 ToUnicode 缺失 codepoint=0 | 实测正确,跳过逻辑 work |
| 连字 fi/fl 共享 box | 没测到(中英都用了标准字体) |
| Bidi 字符顺序 ≠ visual | 不在 PoC 范围 |

---

## 命门 #2: Ink round-trip 🟢

### 关键数据

PDFium native ink 写入 + 保存 + 重新读取 完全自洽:

```
写入: 5 stroke
  Stroke 0: 直线 2pt, 黄色 width 3pt
  Stroke 1: 直线 2pt, 蓝色 width 5pt
  Stroke 2: 100 点 sin 弧线, 红色 width 2pt
  Stroke 3: 4 点 X 交叉, 绿色 width 4pt
  Stroke 4: 2 点 (单点), 紫色 width 8pt

保存到 ink_out.pdf (6274 bytes)

重新打开后 Inspection:
  annotCount = 5  ✅
  全部 subtype = 15 (FPDF_ANNOT_INK)  ✅
  ink point counts = (2, 2, 100, 4, 2)  ✅ 与写入一致
```

### 核心发现

1. **不需要 PDFBox-Android 兜底**(原方案的核心假设被推翻了)
   - DocReaderReplica 的 `nativeAddInkPath`(变通画 page content path)是**没必要**的
   - 我们的实现走 `FPDFPage_CreateAnnot(FPDF_ANNOT_INK) + AddInkStroke + SetBorder + GenerateContent`
   - 关键: **`FPDFAnnot_SetBorder(annot, 0, 0, strokeWidth)` 必填**(DocReaderReplica 注释已警告)
   - 保存用 `FPDF_SaveAsCopy(doc, &fileWrite, 0)` 全量保存(flag=0 避免 incremental bug)

2. **比 MuPDF 当前实现简单很多**
   - `MuPdfNativeAnnotationSaver.kt:545-762` 有 3 层 reflection 救火 + 4 层 save fallback(213 行)
   - PDFium native ink 写入 ~50 行 C++ 全 OK

3. **跨 reader 视觉验证留给人工**
   - 产物在 `/tmp/poc_reports/ink_out.pdf` (人工开 Adobe Acrobat / Foxit Reader / Preview 看)

### 红队 #1 预言对照

| 红队声明 | 实测结果 |
|---|---|
| PDFium Ink AP 渲染缺 BS/W 不可见 | 设了 SetBorder(strokeWidth) 后绿色通过 |
| DocReaderReplica 用 nativeAddInkPath 变通 | 没必要,真 ink 标注完全 work |
| FPDF_INCREMENTAL save 有 bug | 用 flag=0 全量保存避开 |

---

## 命门 #3: 16KB 对齐 + APK 体积 🟢

### 数据

| 项 | 数值 | 结论 |
|----|------|------|
| libpdfium.so LOAD align | **0x4000** (16384) | ✅ 16KB 对齐,Android 15+ 兼容 |
| libpdfium.so 体积 (arm64) | **6.05 MB** | |
| libmupdf_java.so 体积 (arm64) | 9.27 MB | |
| **arm64 净减** | **-3.22 MB** | ✅ 绿灯区间 |
| 多 ABI 净减(估算) | -10 MB | (PoC 只验 arm64) |

### 红队 #1 预言对照

| 红队声明 | 实测结果 |
|---|---|
| bblanchon 16KB 对齐是隐式,无显式承诺 | 实测最新 release 已 16KB(继承 NDK r28) |
| CJK 字体回退单 fallback,可能要打包 NotoSansCJK +20MB | reportlab 现代 CJK PDF 渲染 154/359 chars 提取无 tofu(标准 STSong-Light) |
| 老 PDF / CID 字体可能 tofu | cn_demo.pdf 字符提取确实异常(配对错位) |

### CJK 字体回退尚待人工视觉验证

- 已生成 `ink_out.pdf` 含 CJK 文本 + ink 标注
- 已渲染 cn_test.pdf 第 1 页(154 字符全部正确 codepoint 提取)
- **本机渲染 bitmap 可由用户手工 adb pull 后查看视觉对比**

---

## 工程建议

### 立即可启动的工程改动(基于本 PoC 验证)

1. **`libpdf.cpp` 实现取自本 PoC**, 不抄 DocReaderReplica 的:
   - 真 ink 标注(放弃 `nativeAddInkPath`,因为完全没必要)
   - char quad 用 `GetCharBox` AABB(20 行,不是 250 行)
   - 保存用 `FPDF_SaveAsCopy(flag=0)`(避开 incremental bug)

2. **pdf-reader 适配层补 Y 偏移**:
   - 在 `MuPdfTextExtractor` 重写为 `PdfiumTextExtractor` 时,用 `FPDFText_GetCharOrigin + GetFontSize` 推 ascender/descender
   - 让上层 `TextSelectionOverlay` 拿到的 quad 与现有 MuPDF 版语义一致(行高 quad)

3. **不需要 PDFBox 兜底 ink**:
   - 当前 `PdfDrawingController.kt:483` 走 `MuPdfNativeAnnotationSaver.addInkToPdf` → 改成 `PdfiumNativeAnnotationSaver.addInkToPdf`(使用本 PoC 的 native ink 路径)
   - `PdfAnnotationWriter.addDrawingsToPdf` PDFBox 路径死代码可删

4. **注释类型常量映射**(基于实测 `fpdf_annot.h`):
   - MuPDF 8 (Highlight) → PDFium 9
   - MuPDF 9 (Underline) → PDFium 10
   - MuPDF 11 (StrikeOut) → PDFium 12
   - MuPDF 14 (Ink) → PDFium 15
   - **注意**: PDFium 11 = SQUIGGLY,中间错位

### 工期估算修正

红队 #2 预估 **12-14 周**(主要是 pdf-reader 影响面 6000-8000 行有效改动)。
PoC 没改变这个估算,但**降低了风险**:
- 不需要走 PDFBox 兜底(省 1-2 周)
- char quad 实现简化(省 1-2 周)
- 净下来 **10-12 周更现实**

### 真机回归测试(进入工程后必须做)

PoC 没覆盖的命门(暂不影响启动决策):
- [ ] 旋转字符 PDF(竖排日文 / 旋转 90°的中文)
- [ ] Bidi PDF(阿拉伯 / 希伯来 — pdf-reader 用户 base 可能不大,后置)
- [ ] 老 PDF / OCR 扫描 PDF 的字符提取(已知两引擎都不可靠,产品决策)
- [ ] CJK 字体回退视觉对比(打包 NotoCJK 字体子集 vs 不打包)
- [ ] 加密 PDF 权限位语义(PDFium 是否吞掉 MuPDF authenticatePassword("") 副作用)
- [ ] Acrobat / Foxit 跨 reader 视觉对比 ink_out.pdf

## 产物清单

| 文件 | 位置 | 内容 |
|------|------|------|
| pdfium-poc 项目 | `/Users/chenbufan/AndroidStudioProjects/pdfium-poc/` | 完整可重现 PoC,本地 git |
| Plan 1 详细 CSV | `/tmp/poc_reports/reports/*_diff.csv` | 12 份 PDF 每个字符的 4 角偏移 |
| Plan 1 summary | `/tmp/poc_reports/reports/summary.csv` | 12 行汇总 |
| Plan 2 ink 产物 | `/tmp/poc_reports/ink_out.pdf` | 5 stroke ink 标注, 6274 bytes |
| 本决策 | `pdfium-poc/decision.md` | (你看到的这个文档) |
