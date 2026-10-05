// The iOS counterpart of app/src/main/cpp/jni/{analysis,mel,vocal}_jni.cpp:
// the same calls into native/analyzer, exposed as plain C for Swift. The JSON
// writer is analysis_jni.cpp's, unchanged, so the Kotlin parser
// (TrackFeatures.parse) reads exactly what it reads on Android.

#include "BitChordAnalysis.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "analyzer/audio_analysis.h"
#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"
#include "analyzer/vocal_spectrogram.h"

namespace {

void AppendString(std::string& out, const std::string& value) {
  out += '"';
  for (const char character : value) {
    if (character >= 32 && character < 127 && character != '"' && character != '\\') {
      out += character;
    }
  }
  out += '"';
}

void AppendNumber(std::string& out, double value) {
  if (!(value == value) || value > 1e308 || value < -1e308) {
    out += "null";
    return;
  }
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%.6g", value);
  out += buffer;
}

void AppendDoubles(std::string& out, const std::vector<double>& values) {
  out += '[';
  for (size_t index = 0; index < values.size(); ++index) {
    if (index > 0) out += ',';
    AppendNumber(out, values[index]);
  }
  out += ']';
}

void AppendEnergyCurve(std::string& out, const std::vector<bitchord::smart::EnergyPoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"e\":";
    AppendNumber(out, points[index].energy);
    out += '}';
  }
  out += ']';
}

void AppendCuePoints(std::string& out, const std::vector<bitchord::smart::MixCuePoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"s\":";
    AppendNumber(out, points[index].score);
    out += ",\"y\":";
    AppendString(out, points[index].type);
    out += '}';
  }
  out += ']';
}

void AppendField(std::string& out, const char* name, double value, bool first = false) {
  if (!first) out += ',';
  out += '"';
  out += name;
  out += "\":";
  AppendNumber(out, value);
}

float* CopyOut(const std::vector<float>& values) {
  if (values.empty()) return nullptr;
  float* buffer = static_cast<float*>(malloc(values.size() * sizeof(float)));
  if (buffer != nullptr) memcpy(buffer, values.data(), values.size() * sizeof(float));
  return buffer;
}

}  // namespace

extern "C" {

char* bc_analyze_features(const float* samples, long count, double sample_rate, double duration) {
  if (samples == nullptr || count <= 0) return nullptr;
  const std::vector<float> input(samples, samples + count);
  const bitchord::smart::AnalysisResult result =
      bitchord::smart::AnalyzeAudio(input, sample_rate, duration);

  std::string json;
  json.reserve(8192 + result.energy_curve.size() * 24);
  json += '{';
  AppendField(json, "duration", result.duration, true);
  AppendField(json, "bpm", result.bpm);
  AppendField(json, "beatInterval", result.beat_interval);
  AppendField(json, "firstBeat", result.first_beat);
  AppendField(json, "beatConfidence", result.beat_confidence);
  AppendField(json, "keyConfidence", result.key_confidence);
  AppendField(json, "audibleStartTime", result.audible_start_time);
  AppendField(json, "pickupTime", result.pickup_time);
  AppendField(json, "introEndTime", result.intro_end_time);
  AppendField(json, "outroStartTime", result.outro_start_time);
  AppendField(json, "contentEndTime", result.content_end_time);
  AppendField(json, "mixInTime", result.mix_in_time);
  AppendField(json, "mixOutTime", result.mix_out_time);
  AppendField(json, "vocalProbability", result.vocal_probability);
  json += ",\"key\":";
  AppendString(json, result.key);
  json += ",\"downbeats\":";
  AppendDoubles(json, result.downbeats);
  json += ",\"phraseBoundaries\":";
  AppendDoubles(json, result.phrase_boundaries);
  json += ",\"vocalActivityMask\":";
  AppendDoubles(json, result.vocal_activity_mask);
  json += ",\"energyCurve\":";
  AppendEnergyCurve(json, result.energy_curve);
  json += ",\"lowEnergyCurve\":";
  AppendEnergyCurve(json, result.low_energy_curve);
  json += ",\"mixInCandidates\":";
  AppendCuePoints(json, result.mix_in_candidates);
  json += ",\"mixOutCandidates\":";
  AppendCuePoints(json, result.mix_out_candidates);
  json += '}';

  return strdup(json.c_str());
}

float* bc_resample(const float* samples, long count, double input_rate, double output_rate, long* out_count) {
  if (out_count != nullptr) *out_count = 0;
  if (samples == nullptr || count <= 0) return nullptr;
  const std::vector<float> input(samples, samples + count);
  const std::vector<float> output = bitchord::smart::Resample(input, input_rate, output_rate);
  if (out_count != nullptr) *out_count = static_cast<long>(output.size());
  return CopyOut(output);
}

float* bc_beat_spectrogram(const float* samples, long count, double sample_rate, long* out_frames) {
  if (out_frames != nullptr) *out_frames = 0;
  if (samples == nullptr || count <= 0) return nullptr;
  const std::vector<float> input(samples, samples + count);
  const bitchord::smart::BeatSpectrogram spectrogram =
      bitchord::smart::ComputeBeatSpectrogram(input, sample_rate);
  if (out_frames != nullptr) *out_frames = static_cast<long>(spectrogram.frames);
  return CopyOut(spectrogram.values);
}

long bc_beat_mels(void) {
  return static_cast<long>(bitchord::smart::kBeatSpectrogramMels);
}

float* bc_vocal_spectrogram(const float* left, const float* right, long count, double sample_rate, long* out_frames) {
  if (out_frames != nullptr) *out_frames = 0;
  if (left == nullptr || right == nullptr || count <= 0) return nullptr;
  const std::vector<std::vector<float>> channels = {
    std::vector<float>(left, left + count),
    std::vector<float>(right, right + count),
  };
  const bitchord::smart::VocalSpectrogram spectrogram =
      bitchord::smart::ComputeVocalSpectrogram(channels, sample_rate);
  if (out_frames != nullptr) *out_frames = static_cast<long>(spectrogram.frames);
  return CopyOut(spectrogram.values);
}

long bc_vocal_bins(void) {
  return static_cast<long>(bitchord::smart::kVocalSpectrogramBins);
}

void bc_free(void* pointer) {
  free(pointer);
}

}  // extern "C"
