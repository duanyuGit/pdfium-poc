cmake_minimum_required(VERSION 3.22.1)
project(pdfpoc)

# bblanchon 预编译 libpdfium.so 作为外部库导入
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libpdfium.so
    IMPORTED_NO_SONAME TRUE)

# 自己的薄 JNI 桥
add_library(pdfpoc SHARED libpdf.cpp)
target_include_directories(pdfpoc PRIVATE ${CMAKE_SOURCE_DIR}/pdfium)
target_link_libraries(pdfpoc pdfium android jnigraphics log)
