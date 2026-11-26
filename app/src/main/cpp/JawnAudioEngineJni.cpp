// The bridge between Java and the Rust engine's plain C ABI.
//
// engine/src/oboe_device.rs exports jawn_audio_start/stop/status/free as
// extern "C", not as JNI symbols, so a Java `native` method cannot bind to
// them. This file is compiled into libjawnaudio.so (the library JawnAudioOboe
// already loads), resolves libjawn_audio.so on first use with dlopen/dlsym,
// and exports the Java_com_jawnos_app_JawnAudioEngine_* entry points. The Rust
// library is packaged beside this one by Gradle's default jniLibs source set
// (app/src/main/jniLibs, staged by scripts/build_engine.pl); nothing here links
// it, so a build without it still loads and answers with an error instead of
// throwing. The engine ABI is deliberately unchanged.
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <cstdio>
#include <string>

namespace {

constexpr const char *kTag = "JawnAudioEngineJni";
constexpr const char *kLibrary = "libjawn_audio.so";

using StartFn = char *(*)(int, double, const char *);
using StartPlanFn = char *(*)(int, double, const char *, const char *);
using FixturePlanFn = char *(*)();
using StopFn = char *(*)();
using StatusFn = char *(*)();
using BackendsFn = char *(*)();
// The duplex round-trip probe (2026-11 slice). Optional like backends.
using RoundTripFn = char *(*)(int, int);
// The direct-USB capture+monitor backend. Optional: an engine built before it
// still answers every other call, and nativeStartUsb says so.
using StartUsbFn = char *(*)(int, int, int, int, int, int, int, int, int, double,
                             const char *, const char *);
// Monitor depth for the next USB take, and the USB round-trip probe. Both
// arrived after the take; optional so an older engine still loads.
using SetUsbDepthFn = char *(*)(int, int, int, int);
using RoundTripUsbFn = char *(*)(int, int, int, int, int, int, int, int, int, int);
using FreeFn = void (*)(char *);

struct Engine {
    StartFn start = nullptr;
    // Optional: a plan-carrying start and the fixture bytes (2026-11 slice).
    // A take without a plan still works on an engine that predates them.
    StartPlanFn start_plan = nullptr;
    FixturePlanFn fixture_plan = nullptr;
    StopFn stop = nullptr;
    StatusFn status = nullptr;
    // Optional: an engine built before the backends report still works.
    BackendsFn backends = nullptr;
    // Optional: the duplex probe arrived in the round-trip slice, so an
    // engine built before it still answers every other call.
    RoundTripFn roundtrip = nullptr;
    // Optional: the direct-USB start (USB take slice).
    StartUsbFn start_usb = nullptr;
    // Optional: the monitor depth and the USB round trip (depth/probe slice).
    SetUsbDepthFn set_usb_depth = nullptr;
    RoundTripUsbFn roundtrip_usb = nullptr;
    FreeFn free = nullptr;
    std::string error;
};

std::string escapeJson(const std::string &text) {
    std::string out;
    out.reserve(text.size() + 16);
    for (char raw : text) {
        unsigned char c = static_cast<unsigned char>(raw);
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
                    out += escaped;
                } else {
                    out += raw;
                }
        }
    }
    return out;
}

std::string errorJson(const std::string &message) {
    return "{\"ok\":0,\"error\":\"" + escapeJson(message) + "\"}";
}

// One attempt per process: a library that is missing is not going to appear
// later in this build, and a failure is cached so every call answers the same.
Engine &engine() {
    static Engine cached;
    static bool tried = false;
    if (tried) return cached;
    tried = true;

    // The Rust engine statically links Oboe's C++ and leaves the C++ runtime
    // symbols undefined. This shim already depends on libc++_shared.so, but
    // System.loadLibrary loads it outside the global scope; promote it so the
    // engine's dlopen can resolve those symbols. Best effort: an engine that
    // does not need it still loads without it.
    void *stl = dlopen("libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    if (stl == nullptr) {
        const char *why = dlerror();
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "libc++_shared.so was not promoted to the global scope: %s",
                            why == nullptr ? "unknown error" : why);
    }

    void *handle = dlopen(kLibrary, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *why = dlerror();
        cached.error = std::string("the audio engine is not in this build: ")
                + (why == nullptr ? kLibrary : why);
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s", cached.error.c_str());
        return cached;
    }
    cached.start = reinterpret_cast<StartFn>(dlsym(handle, "jawn_audio_start"));
    cached.start_plan = reinterpret_cast<StartPlanFn>(dlsym(handle, "jawn_audio_start_plan"));
    cached.fixture_plan = reinterpret_cast<FixturePlanFn>(dlsym(handle, "jawn_audio_fixture_plan"));
    cached.stop = reinterpret_cast<StopFn>(dlsym(handle, "jawn_audio_stop"));
    cached.status = reinterpret_cast<StatusFn>(dlsym(handle, "jawn_audio_status"));
    cached.backends = reinterpret_cast<BackendsFn>(dlsym(handle, "jawn_audio_backends"));
    cached.roundtrip = reinterpret_cast<RoundTripFn>(dlsym(handle, "jawn_audio_roundtrip"));
    cached.start_usb = reinterpret_cast<StartUsbFn>(dlsym(handle, "jawn_audio_start_usb"));
    cached.set_usb_depth = reinterpret_cast<SetUsbDepthFn>(
            dlsym(handle, "jawn_audio_set_usb_depth"));
    cached.roundtrip_usb = reinterpret_cast<RoundTripUsbFn>(
            dlsym(handle, "jawn_audio_roundtrip_usb"));
    cached.free = reinterpret_cast<FreeFn>(dlsym(handle, "jawn_audio_free"));
    if (cached.start == nullptr || cached.stop == nullptr || cached.status == nullptr
            || cached.free == nullptr) {
        cached.error = "libjawn_audio.so does not export the jawn_audio_* symbols";
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s", cached.error.c_str());
    }
    return cached;
}

jstring errorString(JNIEnv *env, const std::string &message) {
    return env->NewStringUTF(errorJson(message).c_str());
}

// Take ownership of a Rust string, turn it into a Java string, and free it.
jstring answer(JNIEnv *env, Engine &engine, char *text) {
    if (text == nullptr) {
        return errorString(env, "the audio engine returned no status");
    }
    jstring out = env->NewStringUTF(text);
    engine.free(text);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeStart(
        JNIEnv *env, jclass, jint deviceId, jdouble seconds, jstring out) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);

    const char *path = nullptr;
    if (out != nullptr) path = env->GetStringUTFChars(out, nullptr);
    char *text = engine.start(static_cast<int>(deviceId),
                              static_cast<double>(seconds), path);
    if (path != nullptr) env->ReleaseStringUTFChars(out, path);
    return answer(env, engine, text);
}

// The plan-carrying start. An engine without the symbol still starts a raw
// take when the plan is empty; a plan it cannot run is a sentence, never a
// silently raw recording.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeStartPlan(
        JNIEnv *env, jclass, jint deviceId, jdouble seconds, jstring out, jstring plan) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);

    const char *path = nullptr;
    if (out != nullptr) path = env->GetStringUTFChars(out, nullptr);
    const char *planText = nullptr;
    if (plan != nullptr) planText = env->GetStringUTFChars(plan, nullptr);

    char *text = nullptr;
    if (engine.start_plan != nullptr) {
        text = engine.start_plan(static_cast<int>(deviceId),
                                 static_cast<double>(seconds), path, planText);
    } else if (planText == nullptr || planText[0] == '\0') {
        text = engine.start(static_cast<int>(deviceId),
                            static_cast<double>(seconds), path);
    }

    if (planText != nullptr) env->ReleaseStringUTFChars(plan, planText);
    if (path != nullptr) env->ReleaseStringUTFChars(out, path);
    if (text == nullptr && engine.start_plan == nullptr) {
        return errorString(env, "this engine build has no plan-carrying start");
    }
    return answer(env, engine, text);
}

// The one fixture plan, asked of the engine so Linux and the phone run exactly
// the same bytes.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeFixturePlan(JNIEnv *env, jclass) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.fixture_plan == nullptr) {
        return errorString(env, "this engine build has no fixture plan");
    }
    return answer(env, engine, engine.fixture_plan());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeStop(JNIEnv *env, jclass) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    return answer(env, engine, engine.stop());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeStatus(JNIEnv *env, jclass) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    return answer(env, engine, engine.status());
}

// The optional symbol, so an older engine still answers everything else.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeBackends(JNIEnv *env, jclass) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.backends == nullptr) {
        return errorString(env, "this engine build has no backends report");
    }
    return answer(env, engine, engine.backends());
}

// The duplex round-trip probe. Unlike the take, this call blocks for the
// probe's own capture window (~1.5-3 s), so the Java bridge runs it on a
// worker thread and polls roundtripStatus(); the WebView never waits.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeRoundTrip(
        JNIEnv *env, jclass, jint deviceIn, jint deviceOut) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.roundtrip == nullptr) {
        return errorString(env, "this engine build has no round-trip probe");
    }
    return answer(env, engine, engine.roundtrip(static_cast<int>(deviceIn),
                                               static_cast<int>(deviceOut)));
}


// The direct-USB take. Optional, like backends and roundtrip: an engine built
// before the USB backend still answers everything else, and this entry point
// says the backend is absent instead of failing to link.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeStartUsb(
        JNIEnv *env, jclass, jint fd,
        jint ifaceIn, jint altIn, jint epIn, jint packetIn,
        jint ifaceOut, jint altOut, jint epOut, jint packetOut,
        jdouble seconds, jstring out, jstring plan) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.start_usb == nullptr) {
        return errorString(env, "this engine build has no USB backend");
    }

    const char *path = nullptr;
    if (out != nullptr) path = env->GetStringUTFChars(out, nullptr);
    const char *planText = nullptr;
    if (plan != nullptr) planText = env->GetStringUTFChars(plan, nullptr);

    char *text = engine.start_usb(
            static_cast<int>(fd), static_cast<int>(ifaceIn), static_cast<int>(altIn),
            static_cast<int>(epIn), static_cast<int>(packetIn),
            static_cast<int>(ifaceOut), static_cast<int>(altOut),
            static_cast<int>(epOut), static_cast<int>(packetOut),
            static_cast<double>(seconds), path, planText);

    if (planText != nullptr) env->ReleaseStringUTFChars(plan, planText);
    if (path != nullptr) env->ReleaseStringUTFChars(out, path);
    return answer(env, engine, text);
}

// The next USB take's monitor depth. Optional, like backends and roundtrip:
// an engine built before it answers a sentence instead of failing to link.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeSetUsbDepth(
        JNIEnv *env, jclass, jint inUrbs, jint inPackets,
        jint outUrbs, jint outPackets) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.set_usb_depth == nullptr) {
        return errorString(env, "this engine build has no USB depth control");
    }
    return answer(env, engine, engine.set_usb_depth(
            static_cast<int>(inUrbs), static_cast<int>(inPackets),
            static_cast<int>(outUrbs), static_cast<int>(outPackets)));
}

// The direct-USB round trip. It blocks for its own window (~1.5 s) because the
// probe runs on a worker thread, and it never closes the fd: the probe still
// owns the connection and releases its claims afterwards.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jawnos_app_JawnAudioEngine_nativeRoundTripUsb(
        JNIEnv *env, jclass, jint fd,
        jint ifaceIn, jint altIn, jint epIn, jint packetIn,
        jint ifaceOut, jint altOut, jint epOut, jint packetOut, jint millis) {
    Engine &engine = ::engine();
    if (!engine.error.empty()) return errorString(env, engine.error);
    if (engine.roundtrip_usb == nullptr) {
        return errorString(env, "this engine build has no USB round-trip probe");
    }
    return answer(env, engine, engine.roundtrip_usb(
            static_cast<int>(fd),
            static_cast<int>(ifaceIn), static_cast<int>(altIn),
            static_cast<int>(epIn), static_cast<int>(packetIn),
            static_cast<int>(ifaceOut), static_cast<int>(altOut),
            static_cast<int>(epOut), static_cast<int>(packetOut),
            static_cast<int>(millis)));
}
