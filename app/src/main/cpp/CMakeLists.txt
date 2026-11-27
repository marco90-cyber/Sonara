cmake_minimum_required(VERSION 3.18)
project(mlplayerdsp C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libsoxr (LGPL-2.1+): ricampionatore di alta qualità, senza SIMD né OpenMP
set(SOXR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/soxr)
add_library(soxr STATIC
    ${SOXR_DIR}/soxr.c ${SOXR_DIR}/data-io.c ${SOXR_DIR}/dbesi0.c ${SOXR_DIR}/filter.c
    ${SOXR_DIR}/fft4g64.c ${SOXR_DIR}/fft4g32.c ${SOXR_DIR}/cr.c ${SOXR_DIR}/cr32.c ${SOXR_DIR}/cr64.c)
target_include_directories(soxr PRIVATE ${SOXR_DIR})
target_compile_definitions(soxr PRIVATE SOXR_LIB NDEBUG)
target_compile_options(soxr PRIVATE -O3 -w -fPIC)

# Oboe (AAudio): uscita esclusiva MMAP sulle uscite del telefono (DirectOutputStream, da LastWave)
find_package(oboe REQUIRED CONFIG)

add_library(mlplayerdsp SHARED mlplayer_dsp_jni.cpp DirectOutputStream.cpp DirectOutputBridge.cpp)
target_include_directories(mlplayerdsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(mlplayerdsp soxr oboe::oboe log)
target_compile_options(mlplayerdsp PRIVATE -O3 -fno-math-errno)
# Android 15: pagine da 16 KB
target_link_options(mlplayerdsp PRIVATE -Wl,-z,max-page-size=16384)


# whisper.cpp (MIT): riconoscimento vocale offline per generare i testi. Solo arm64: sulle altre architetture
# la libreria c'è ma dice "non disponibile". Codice ufficiale, versione fissata, scaricato in fase di build.
add_library(sonarawhisper SHARED whisper_jni.cpp)
target_link_libraries(sonarawhisper log)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    include(FetchContent)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(whisper_cpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG v1.8.7
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(whisper_cpp)
    target_link_libraries(sonarawhisper whisper)
    target_compile_definitions(sonarawhisper PRIVATE WHISPER_ENABLED)
endif()
target_compile_options(sonarawhisper PRIVATE -O3)
target_link_options(sonarawhisper PRIVATE -Wl,-z,max-page-size=16384)
