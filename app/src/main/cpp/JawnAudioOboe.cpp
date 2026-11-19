// window.JawnAudioOboe's native half: a full-duplex Oboe stream that monitors
// the phone's microphone through its own output while reporting the facts the
// page cannot get from getUserMedia (granted sharing/performance mode, rate,
// frames per burst, buffer, xruns).
//
// Oboe no longer has a Direction::FullDuplex: AAudio's direction is input or
// output, and the library's own duplex recipe is two streams with the input
// read from the output stream's data callback. This follows it directly - an
// input stream and an output stream are opened together, the input is drained
// once at start, then every callback reads exactly what the output needs.
//
// The data callback never allocates, locks or logs: the input buffer is sized
// before the stream starts, the gain and the counters are atomics, and a short
// read is padded with silence rather than retried. The lifecycle mutex is only
// taken by start/stop/stats on the bridge's thread.

#include <jni.h>

#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char *kTag = "JawnAudioOboe";
constexpr int32_t kDefaultSampleRate = 48000;
constexpr int32_t kDefaultFramesPerBurst = 192; // 4 ms at 48 kHz
constexpr int32_t kMaxCallbackFrames = 8192;
constexpr int32_t kPrimeCallbacks = 8;
constexpr float kMaxGain = 4.0f;

const char *sharingText(oboe::SharingMode mode) {
    switch (mode) {
        case oboe::SharingMode::Exclusive: return "Exclusive";
        case oboe::SharingMode::Shared: return "Shared";
    }
    return "Unknown";
}

const char *performanceText(oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::None: return "None";
        case oboe::PerformanceMode::PowerSaving: return "PowerSaving";
        case oboe::PerformanceMode::LowLatency: return "LowLatency";
        case oboe::PerformanceMode::PowerSavingOffloaded: return "PowerSavingOffloaded";
    }
    return "Unknown";
}

const char *presetText(oboe::InputPreset preset) {
    switch (preset) {
        case oboe::InputPreset::Generic: return "Generic";
        case oboe::InputPreset::Camcorder: return "Camcorder";
        case oboe::InputPreset::VoiceRecognition: return "VoiceRecognition";
        case oboe::InputPreset::VoiceCommunication: return "VoiceCommunication";
        case oboe::InputPreset::Unprocessed: return "Unprocessed";
        case oboe::InputPreset::VoicePerformance: return "VoicePerformance";
    }
    return "Unknown";
}

const char *stateText(oboe::StreamState state) {
    switch (state) {
        case oboe::StreamState::Uninitialized: return "Uninitialized";
        case oboe::StreamState::Unknown: return "Unknown";
        case oboe::StreamState::Open: return "Open";
        case oboe::StreamState::Starting: return "Starting";
        case oboe::StreamState::Started: return "Started";
        case oboe::StreamState::Pausing: return "Pausing";
        case oboe::StreamState::Paused: return "Paused";
        case oboe::StreamState::Flushing: return "Flushing";
        case oboe::StreamState::Flushed: return "Flushed";
        case oboe::StreamState::Stopping: return "Stopping";
        case oboe::StreamState::Stopped: return "Stopped";
        case oboe::StreamState::Closing: return "Closing";
        case oboe::StreamState::Closed: return "Closed";
        case oboe::StreamState::Disconnected: return "Disconnected";
    }
    return "Unknown";
}

// The bridge turns these strings into the page's error line, so they are
// spelled out rather than taken from Oboe's debug-only convertToText().
std::string resultText(oboe::Result result) {
    switch (result) {
        case oboe::Result::OK: return "OK";
        case oboe::Result::ErrorDisconnected: return "the audio device disconnected";
        case oboe::Result::ErrorIllegalArgument: return "the audio device refused the request";
        case oboe::Result::ErrorInternal: return "an internal audio error";
        case oboe::Result::ErrorInvalidState: return "the stream was in the wrong state";
        case oboe::Result::ErrorInvalidHandle: return "the stream handle was invalid";
        case oboe::Result::ErrorUnimplemented: return "the audio device does not implement this";
        case oboe::Result::ErrorUnavailable: return "the audio device is unavailable";
        case oboe::Result::ErrorNoFreeHandles: return "the audio device is out of handles";
        case oboe::Result::ErrorNoMemory: return "the audio device is out of memory";
        case oboe::Result::ErrorNull: return "the audio device returned nothing";
        case oboe::Result::ErrorTimeout: return "the audio device timed out";
        case oboe::Result::ErrorWouldBlock: return "the audio device would block";
        case oboe::Result::ErrorInvalidFormat: return "the audio format was refused";
        case oboe::Result::ErrorOutOfRange: return "an audio value was out of range";
        case oboe::Result::ErrorNoService: return "the audio service is not running";
        case oboe::Result::ErrorInvalidRate: return "the sample rate was refused";
        case oboe::Result::ErrorClosed: return "the stream was closed";
        default: break;
    }
    char buf[48];
    std::snprintf(buf, sizeof(buf), "audio error %d", static_cast<int>(result));
    return buf;
}

std::string escape(const std::string &text) {
    std::string out;
    out.reserve(text.size() + 8);
    for (char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) out += ' ';
                else out += c;
        }
    }
    return out;
}

int32_t xRuns(oboe::AudioStream *stream) {
    if (stream == nullptr) return -1;
    auto count = stream->getXRunCount();
    return count ? count.value() : -1;
}

struct OpenResult {
    std::shared_ptr<oboe::AudioStream> stream;
    std::string error;
};

class Monitor : public oboe::AudioStreamDataCallback,
                public oboe::AudioStreamErrorCallback {
public:
    std::string start(int32_t sampleRate, int32_t framesPerBurst, float gain) {
        std::lock_guard<std::mutex> guard(lifecycle_);
        closeLocked();

        if (sampleRate <= 0) sampleRate = kDefaultSampleRate;
        if (framesPerBurst <= 0) framesPerBurst = kDefaultFramesPerBurst;
        // NaN-safe clamp: a page mistake must not become a silent stream.
        if (!(gain >= 0.0f)) gain = 0.0f;
        if (gain > kMaxGain) gain = kMaxGain;
        gain_.store(gain, std::memory_order_relaxed);
        requestedRate_ = sampleRate;
        requestedBurst_ = framesPerBurst;
        frames_.store(0, std::memory_order_relaxed);
        primeCallbacks_.store(0, std::memory_order_relaxed);
        priming_.store(true, std::memory_order_relaxed);
        lastErrorCode_.store(0, std::memory_order_relaxed);
        lastErrorText_.clear();

        OpenResult input = openInput(sampleRate);
        if (!input.stream) {
            lastErrorText_ = input.error;
            __android_log_print(ANDROID_LOG_WARN, kTag, "input: %s", input.error.c_str());
            return errorJson(input.error);
        }

        OpenResult output = openOutput(sampleRate, framesPerBurst);
        if (!output.stream) {
            input.stream->close();
            lastErrorText_ = output.error;
            __android_log_print(ANDROID_LOG_WARN, kTag, "output: %s", output.error.c_str());
            return errorJson(output.error);
        }

        // Buffer sized once, before the callback can run.
        inChannels_.store(std::max(1, input.stream->getChannelCount()), std::memory_order_relaxed);
        outChannels_.store(std::max(1, output.stream->getChannelCount()), std::memory_order_relaxed);
        inputBuffer_.assign(static_cast<size_t>(kMaxCallbackFrames) *
                                    static_cast<size_t>(inChannels_.load()),
                            0.0f);
        input_ = std::move(input.stream);
        output_ = std::move(output.stream);
        systemVolumeApplies_.store(
                output_->getSharingMode() == oboe::SharingMode::Exclusive,
                std::memory_order_relaxed);

        oboe::Result started = input_->requestStart();
        if (started != oboe::Result::OK) {
            std::string why = "the microphone would not start: " + resultText(started);
            lastErrorText_ = why;
            closeLocked();
            return errorJson(why);
        }
        started = output_->requestStart();
        if (started != oboe::Result::OK) {
            std::string why = "the monitor would not start: " + resultText(started);
            lastErrorText_ = why;
            closeLocked();
            return errorJson(why);
        }

        // Ask for a tight output buffer once the stream is running; a device
        // that refuses keeps the buffer it granted, which stats() reports.
        output_->setBufferSizeInFrames(framesPerBurst * 2);

        return statsLocked(true);
    }

    std::string stop() {
        std::lock_guard<std::mutex> guard(lifecycle_);
        closeLocked();
        return statsLocked(true);
    }

    std::string stats(bool microphoneGranted) {
        std::lock_guard<std::mutex> guard(lifecycle_);
        return statsLocked(microphoneGranted);
    }

    void setGain(float gain) {
        if (!(gain >= 0.0f)) gain = 0.0f;
        if (gain > kMaxGain) gain = kMaxGain;
        gain_.store(gain, std::memory_order_relaxed);
    }

    // The phone's media level, 0..1. Applied only when the output is exclusive:
    // a shared stream is mixed by AudioFlinger, which already applies it.
    void setSystemVolume(float volume) {
        if (!(volume >= 0.0f)) volume = 0.0f;
        if (volume > 1.0f) volume = 1.0f;
        systemVolume_.store(volume, std::memory_order_relaxed);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream * /*output*/,
                                          void *audioData,
                                          int32_t numFrames) override {
        auto *out = static_cast<float *>(audioData);
        if (out == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;

        const int32_t outChannels = outChannels_.load(std::memory_order_relaxed);
        const float gain = gain_.load(std::memory_order_relaxed);
        const bool applySystem = systemVolumeApplies_.load(std::memory_order_relaxed);
        const float level =
                applySystem ? systemVolume_.load(std::memory_order_relaxed) : 1.0f;
        const float mix = gain * level;
        oboe::AudioStream *input = input_.get();

        if (priming_.load(std::memory_order_relaxed)) {
            // The input starts before the output. Discard a few callbacks so
            // the first audible one begins at the head of the input buffer.
            if (input != nullptr) {
                for (int i = 0; i < 8; ++i) {
                    auto read = input->read(inputBuffer_.data(), kMaxCallbackFrames, 0);
                    if (!read || read.value() <= 0) break;
                }
            }
            std::memset(out, 0, sizeof(float) * static_cast<size_t>(numFrames) *
                                         static_cast<size_t>(outChannels));
            if (primeCallbacks_.fetch_add(1, std::memory_order_relaxed) + 1 >= kPrimeCallbacks) {
                priming_.store(false, std::memory_order_relaxed);
            }
            return oboe::DataCallbackResult::Continue;
        }

        const int32_t inChannels = inChannels_.load(std::memory_order_relaxed);
        int32_t want = numFrames;
        if (want > kMaxCallbackFrames) want = kMaxCallbackFrames;
        int32_t got = 0;
        if (input != nullptr && !inputBuffer_.empty()) {
            auto read = input->read(inputBuffer_.data(), want, 0);
            if (read) got = read.value();
        }
        if (got < 0) got = 0;
        if (got > want) got = want;

        for (int32_t i = 0; i < numFrames; ++i) {
            const float sample =
                    (i < got) ? inputBuffer_[static_cast<size_t>(i) * inChannels] * mix : 0.0f;
            for (int32_t c = 0; c < outChannels; ++c) {
                out[static_cast<size_t>(i) * outChannels + c] = sample;
            }
        }
        frames_.fetch_add(numFrames, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) override {
        // The output stream is gone. The error callback must not take the
        // lifecycle mutex - close() may be holding it and waiting for this
        // callback to return - so the microphone is stopped from a short
        // helper thread instead. That thread takes the mutex, so it can never
        // touch a stream that stop() is closing.
        lastErrorCode_.store(static_cast<int32_t>(error), std::memory_order_relaxed);
        if (cleanupSpawned_.exchange(true, std::memory_order_relaxed)) return;
        std::thread([this]() {
            std::lock_guard<std::mutex> guard(lifecycle_);
            if (input_ != nullptr) input_->requestStop();
            cleanupSpawned_.store(false, std::memory_order_relaxed);
        }).detach();
    }

private:
    OpenResult openInput(int32_t sampleRate) {
        // Exclusive first (MMAP, no system mixer), then shared; Unprocessed
        // first (no AEC/NS/AGC), then the two presets that still avoid AGC.
        const oboe::SharingMode modes[] = {oboe::SharingMode::Exclusive,
                                           oboe::SharingMode::Shared};
        const oboe::InputPreset presets[] = {oboe::InputPreset::Unprocessed,
                                             oboe::InputPreset::VoicePerformance,
                                             oboe::InputPreset::VoiceRecognition};
        std::string last = "could not open the microphone";
        for (oboe::SharingMode mode : modes) {
            for (oboe::InputPreset preset : presets) {
                oboe::AudioStreamBuilder builder;
                builder.setDirection(oboe::Direction::Input)
                        ->setAudioApi(oboe::AudioApi::AAudio)
                        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                        ->setSharingMode(mode)
                        ->setFormat(oboe::AudioFormat::Float)
                        ->setFormatConversionAllowed(true)
                        ->setChannelConversionAllowed(true)
                        ->setChannelCount(1)
                        ->setSampleRate(sampleRate)
                        ->setInputPreset(preset);
                std::shared_ptr<oboe::AudioStream> stream;
                oboe::Result result = builder.openStream(stream);
                if (result == oboe::Result::OK && stream) {
                    return {std::move(stream), ""};
                }
                last = resultText(result);
            }
        }
        return {nullptr, "microphone: " + last};
    }

    OpenResult openOutput(int32_t sampleRate, int32_t framesPerBurst) {
        const oboe::SharingMode modes[] = {oboe::SharingMode::Exclusive,
                                           oboe::SharingMode::Shared};
        std::string last = "could not open the monitor";
        for (oboe::SharingMode mode : modes) {
            oboe::AudioStreamBuilder builder;
            builder.setDirection(oboe::Direction::Output)
                    ->setAudioApi(oboe::AudioApi::AAudio)
                    ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                    ->setSharingMode(mode)
                    ->setFormat(oboe::AudioFormat::Float)
                    ->setFormatConversionAllowed(true)
                    ->setChannelConversionAllowed(true)
                    ->setChannelCount(2)
                    ->setSampleRate(sampleRate)
                    ->setFramesPerDataCallback(framesPerBurst)
                    ->setDataCallback(this)
                    ->setErrorCallback(this);
            std::shared_ptr<oboe::AudioStream> stream;
            oboe::Result result = builder.openStream(stream);
            if (result == oboe::Result::OK && stream) {
                return {std::move(stream), ""};
            }
            last = resultText(result);
        }
        return {nullptr, "monitor: " + last};
    }

    void closeLocked() {
        if (output_) {
            output_->requestStop();
            output_->close();
            output_.reset();
        }
        if (input_) {
            input_->requestStop();
            input_->close();
            input_.reset();
        }
    }

    std::string statsLocked(bool microphoneGranted) const {
        oboe::AudioStream *input = input_.get();
        oboe::AudioStream *output = output_.get();
        const bool running = input != nullptr && output != nullptr &&
                             input->getState() == oboe::StreamState::Started &&
                             output->getState() == oboe::StreamState::Started;

        std::ostringstream out;
        out << "{\"ok\":1"
            << ",\"running\":" << (running ? "true" : "false")
            << ",\"microphone\":" << (microphoneGranted ? "true" : "false")
            << ",\"frames\":" << frames_.load(std::memory_order_relaxed)
            << ",\"gain\":" << gain_.load(std::memory_order_relaxed)
            << ",\"systemVolume\":" << systemVolume_.load(std::memory_order_relaxed)
            << ",\"systemVolumeApplied\":"
            << (systemVolumeApplies_.load(std::memory_order_relaxed) ? "true" : "false");

        if (input != nullptr) {
            out << ",\"sampleRate\":" << input->getSampleRate()
                << ",\"framesPerBurst\":" << input->getFramesPerBurst()
                << ",\"bufferSizeInFrames\":" << input->getBufferSizeInFrames()
                << ",\"xRunCount\":" << xRuns(input)
                << ",\"sharingMode\":\"" << sharingText(input->getSharingMode()) << "\""
                << ",\"performanceMode\":\"" << performanceText(input->getPerformanceMode())
                << "\""
                << ",\"inputPreset\":\"" << presetText(input->getInputPreset()) << "\""
                << ",\"inputChannels\":" << input->getChannelCount()
                << ",\"state\":\"" << stateText(input->getState()) << "\"";
        } else {
            out << ",\"sampleRate\":" << requestedRate_
                << ",\"framesPerBurst\":" << requestedBurst_
                << ",\"bufferSizeInFrames\":0"
                << ",\"xRunCount\":-1"
                << ",\"sharingMode\":\"none\""
                << ",\"performanceMode\":\"none\""
                << ",\"inputPreset\":\"none\""
                << ",\"inputChannels\":0"
                << ",\"state\":\"closed\"";
        }

        // The monitor may have been granted a different mode than the capture
        // side; the page is told both rather than one side's number for both.
        if (output != nullptr) {
            out << ",\"output\":{"
                << "\"sampleRate\":" << output->getSampleRate()
                << ",\"framesPerBurst\":" << output->getFramesPerBurst()
                << ",\"bufferSizeInFrames\":" << output->getBufferSizeInFrames()
                << ",\"xRunCount\":" << xRuns(output)
                << ",\"sharingMode\":\"" << sharingText(output->getSharingMode()) << "\""
                << ",\"performanceMode\":\"" << performanceText(output->getPerformanceMode())
                << "\""
                << ",\"channels\":" << output->getChannelCount()
                << ",\"state\":\"" << stateText(output->getState()) << "\""
                << "}";
        } else {
            out << ",\"output\":null";
        }

        std::string error = lastErrorText_;
        if (error.empty()) {
            int32_t code = lastErrorCode_.load(std::memory_order_relaxed);
            if (code != 0) error = resultText(static_cast<oboe::Result>(code));
        }
        out << ",\"error\":\"" << escape(error) << "\""
            << "}";
        return out.str();
    }

    std::string errorJson(const std::string &error) const {
        std::ostringstream out;
        out << "{\"ok\":0,\"running\":false,\"frames\":"
            << frames_.load(std::memory_order_relaxed)
            << ",\"error\":\"" << escape(error) << "\"}";
        return out.str();
    }

    mutable std::mutex lifecycle_;
    std::shared_ptr<oboe::AudioStream> input_;
    std::shared_ptr<oboe::AudioStream> output_;
    std::vector<float> inputBuffer_;
    std::atomic<float> gain_{1.0f};
    std::atomic<float> systemVolume_{1.0f};
    std::atomic<bool> systemVolumeApplies_{false};
    std::atomic<int64_t> frames_{0};
    std::atomic<int32_t> inChannels_{1};
    std::atomic<int32_t> outChannels_{1};
    std::atomic<bool> priming_{false};
    std::atomic<int32_t> primeCallbacks_{0};
    std::atomic<int32_t> lastErrorCode_{0};
    std::atomic<bool> cleanupSpawned_{false};
    std::string lastErrorText_;
    int32_t requestedRate_ = kDefaultSampleRate;
    int32_t requestedBurst_ = kDefaultFramesPerBurst;
};

std::mutex gLock;
std::unique_ptr<Monitor> gMonitor;

jstring toJson(JNIEnv *env, const std::string &json) {
    return env->NewStringUTF(json.c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioOboe_nativeStart(JNIEnv *env, jclass, jint sampleRate,
                                              jint framesPerBurst, jfloat gain) {
    std::lock_guard<std::mutex> guard(gLock);
    if (!gMonitor) gMonitor = std::make_unique<Monitor>();
    return toJson(env, gMonitor->start(sampleRate, framesPerBurst, gain));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioOboe_nativeStop(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> guard(gLock);
    if (!gMonitor) {
        return toJson(env, "{\"ok\":1,\"running\":false,\"error\":\"\"}");
    }
    return toJson(env, gMonitor->stop());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioOboe_nativeStats(JNIEnv *env, jclass, jboolean microphoneGranted) {
    std::lock_guard<std::mutex> guard(gLock);
    if (!gMonitor) {
        return toJson(env, "{\"ok\":1,\"running\":false,\"microphone\":"
                           + std::string(microphoneGranted ? "true" : "false")
                           + ",\"state\":\"closed\",\"error\":\"\"}");
    }
    return toJson(env, gMonitor->stats(microphoneGranted));
}

extern "C" JNIEXPORT void JNICALL
Java_com_jawnos_app_JawnAudioOboe_nativeSetGain(JNIEnv *, jclass, jfloat gain) {
    std::lock_guard<std::mutex> guard(gLock);
    if (gMonitor) gMonitor->setGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jawnos_app_JawnAudioOboe_nativeSetSystemVolume(JNIEnv *, jclass, jfloat volume) {
    std::lock_guard<std::mutex> guard(gLock);
    if (gMonitor) gMonitor->setSystemVolume(volume);
}
