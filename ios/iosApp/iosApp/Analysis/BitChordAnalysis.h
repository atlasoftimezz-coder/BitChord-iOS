// C entry points into the Automix analyzer (native/analyzer, shared with the
// Android app), for Swift. The iOS counterpart of app/src/main/cpp/jni/*.cpp:
// same functions, plain C instead of JNI. Every returned buffer is malloc'd and
// must be released with bc_free.

#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/// Whole-track DSP analysis of mono PCM at `sample_rate` (11025 expected), as
/// the JSON document Android's analysis_jni.cpp writes. NULL on failure.
char *bc_analyze_features(const float *samples, long count, double sample_rate, double duration);

/// Windowed-sinc resampling of mono PCM. `*out_count` receives the length.
float *bc_resample(const float *samples, long count, double input_rate, double output_rate, long *out_count);

/// The Beat This! log-mel front end over mono PCM at 22050 Hz: row-major
/// [frames][bc_beat_mels()]. `*out_frames` receives the frame count.
float *bc_beat_spectrogram(const float *samples, long count, double sample_rate, long *out_frames);
long bc_beat_mels(void);

/// open-unmix's magnitude STFT over planar stereo at 44100 Hz: bin-major
/// [2][bc_vocal_bins()][frames]. `*out_frames` receives the frame count.
float *bc_vocal_spectrogram(const float *left, const float *right, long count, double sample_rate, long *out_frames);
long bc_vocal_bins(void);

void bc_free(void *pointer);

#ifdef __cplusplus
}
#endif
