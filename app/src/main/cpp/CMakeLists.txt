cmake_minimum_required(VERSION 3.18.1)
project(voiceiso C)

# RNNoise v0.1.1 (BSD licence, see rnnoise/COPYING): small recurrent network that removes
# background noise from speech, 48 kHz, 10 ms frames.
add_library(voiceiso SHARED
    voice_isolation.c
    rnnoise/denoise.c
    rnnoise/rnn.c
    rnnoise/rnn_data.c
    rnnoise/pitch.c
    rnnoise/kiss_fft.c
    rnnoise/celt_lpc.c)
target_include_directories(voiceiso PRIVATE rnnoise)
target_compile_options(voiceiso PRIVATE -O3 -w)
target_link_libraries(voiceiso m)
