cmake_minimum_required(VERSION 3.22.1)
project(michelina_audio LANGUAGES C CXX)

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rnnoise)

add_library(
    michelina_audio
    SHARED
    rnnoise_jni.cpp
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
    michelina_audio
    PRIVATE
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
)

target_compile_definitions(michelina_audio PRIVATE RNNOISE_BUILD)
target_compile_options(michelina_audio PRIVATE -O3 -fvisibility=hidden)
target_compile_features(michelina_audio PRIVATE c_std_99 cxx_std_17)

find_library(LOG_LIBRARY log)
find_library(MATH_LIBRARY m)
target_link_libraries(michelina_audio ${LOG_LIBRARY} ${MATH_LIBRARY})
