# pdfium-poc

PDFium PoC for `pdf-reader` AGPL → BSD 迁移可行性验证。

## 上下文

详见 [pdf-reader/docs/poc/README.md](../pdf-reader/docs/poc/README.md)
(PR #3: https://github.com/dotsail/pdf-reader/pull/3)。

本项目独立于 `pdf-reader` 主项目,仅用于验证 3 个命门级技术风险:
- Plan 1: CJK char-level quad 精度
- Plan 2: Ink round-trip 跨 Acrobat/Foxit
- Plan 3: 16KB 对齐 + APK 体积

## 首次构建

```bash
# 1. 下载 bblanchon pdfium-binaries arm64 预编译
curl -L -o /tmp/pdfium-arm64.tgz \
  "https://github.com/bblanchon/pdfium-binaries/releases/latest/download/pdfium-android-arm64.tgz"
mkdir -p /tmp/pdfium-extract
tar -xzf /tmp/pdfium-arm64.tgz -C /tmp/pdfium-extract

# 2. 头文件 + SO 拷到项目
cp -r /tmp/pdfium-extract/include app/src/main/cpp/pdfium
cp /tmp/pdfium-extract/lib/libpdfium.so app/src/main/jniLibs/arm64-v8a/

# 3. 验证 16KB 对齐(Plan 3)
$ANDROID_HOME/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf \
  -l app/src/main/jniLibs/arm64-v8a/libpdfium.so | grep -E "LOAD" | head -3
# 期望 align=0x4000 (16384 = 16KB)

# 4. 构建
./gradlew :app:assembleDebug
```

## 在真机跑

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push test.pdf /sdcard/Android/data/com.example.pdfium.poc/files/test.pdf
adb shell am start -n com.example.pdfium.poc/.MainActivity
adb logcat -s PdfPoc PdfPocMain
```

## 项目结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt        # 链接 pdfium + 自有 libpdf
│   ├── libpdf.cpp            # JNI 桥(Day 1 基础 + Day 2 char quad)
│   └── pdfium/               # bblanchon headers (gitignored, 本地下载)
├── jniLibs/arm64-v8a/
│   └── libpdfium.so          # bblanchon prebuild (gitignored, 本地下载)
├── java/com/example/pdfium/poc/
│   ├── PdfiumCore.kt         # Kotlin 接口
│   └── MainActivity.kt       # PoC 验证入口
└── res/values/strings.xml
```

## 当前进度

- [x] Day 1 (Plan 0): 项目骨架 + 基础生命周期 + 单页渲染
- [x] Day 2 上午 (Plan 1): nativeGetPageCharQuads C++ + Kotlin 接口
- [ ] Day 2 下午 (Plan 1): MuPDF 对比脚手架 + CjkQuadComparator
- [ ] Day 3 (Plan 1): 跑 10 份 dogfood PDF + 生成 cjk_quad_diff_report.csv
- [ ] Day 4 (Plan 2): Ink round-trip
- [ ] Day 5 (Plan 3): APK 体积 + 字体回退
