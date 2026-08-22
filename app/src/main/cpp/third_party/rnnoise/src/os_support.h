/* Android compatibility shim for the OPUS_CLEAR helper used by RNNoise's NEON path. */
#ifndef RNNOISE_ANDROID_OS_SUPPORT_H
#define RNNOISE_ANDROID_OS_SUPPORT_H

#include <string.h>

#ifndef OPUS_CLEAR
#define OPUS_CLEAR(destination, count) \
    memset((destination), 0, (count) * sizeof(*(destination)))
#endif

#endif
