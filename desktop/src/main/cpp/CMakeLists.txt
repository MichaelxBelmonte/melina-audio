cmake_minimum_required(VERSION 3.22.1)
project(michelina_desktop_audio LANGUAGES C CXX)

# The native bridge only consumes JNI headers. Asking FindJNI for its default
# component set would also require AWT, which is unrelated to the bridge and
# prevents otherwise valid cross-architecture builds (for example macOS x64
# from an Apple Silicon host).
find_package(JNI REQUIRED COMPONENTS JVM)

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp/third_party/rnnoise)

add_library(
    michelina_desktop_audio
    SHARED
    rnnoise_desktop_jni.cpp
    ulunas_desktop_jni.cpp
    deepfilter_desktop_jni.cpp
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/rnnoise_data.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
)

target_include_directories(
    michelina_desktop_audio
    PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
)

target_compile_definitions(michelina_desktop_audio PRIVATE RNNOISE_BUILD)
target_compile_features(michelina_desktop_audio PRIVATE c_std_99 cxx_std_17)
if(MSVC)
    target_compile_options(michelina_desktop_audio PRIVATE /O2)
else()
    target_compile_options(michelina_desktop_audio PRIVATE -O3 -fvisibility=hidden)
    target_link_libraries(michelina_desktop_audio PRIVATE m ${CMAKE_DL_LIBS})
endif()

if(DEFINED MICHELINA_NATIVE_OUTPUT_DIR)
    set_target_properties(
        michelina_desktop_audio
        PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${MICHELINA_NATIVE_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${MICHELINA_NATIVE_OUTPUT_DIR}"
    )
    foreach(CONFIG_TYPE Debug Release RelWithDebInfo MinSizeRel)
        string(TOUPPER "${CONFIG_TYPE}" CONFIG_UPPER)
        set_target_properties(
            michelina_desktop_audio
            PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${CONFIG_UPPER} "${MICHELINA_NATIVE_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${CONFIG_UPPER} "${MICHELINA_NATIVE_OUTPUT_DIR}"
        )
    endforeach()
endif()
