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
using StopFn = char *(*)();
using StatusFn = char *(*)();
using FreeFn = void (*)(char *);

struct Engine {
    StartFn start = nullptr;
    StopFn stop = nullptr;
    StatusFn status = nullptr;
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

    void *handle = dlopen(kLibrary, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *why = dlerror();
        cached.error = std::string("the audio engine is not in this build: ")
                + (why == nullptr ? kLibrary : why);
        __android_log_print(ANDROID_LOG_WARN, kTag, "%s", cached.error.c_str());
        return cached;
    }
    cached.start = reinterpret_cast<StartFn>(dlsym(handle, "jawn_audio_start"));
    cached.stop = reinterpret_cast<StopFn>(dlsym(handle, "jawn_audio_stop"));
    cached.status = reinterpret_cast<StatusFn>(dlsym(handle, "jawn_audio_status"));
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
