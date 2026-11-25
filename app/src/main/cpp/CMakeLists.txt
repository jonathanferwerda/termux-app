# The Studio page's native low-latency path. Oboe arrives as a prefab package
# from the Gradle dependency (com.google.oboe:oboe), so this file compiles the
# C++ monitor bridge and its JNI shim, and links the library the AAR ships. The
# APK is arm64-v8a only, and the ndk { abiFilters } in app/build.gradle is what
# CMake builds.
#
# JawnAudioEngineJni.cpp exports the Java_com_jawnos_app_JawnAudioEngine_*
# symbols and dlopen()s the Rust engine's libjawn_audio.so at first use; it does
# not link that library (docs/studio-oboe-slice.md §5).
cmake_minimum_required(VERSION 3.22.1)

project(jawnaudio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(oboe REQUIRED CONFIG)

add_library(jawnaudio SHARED JawnAudioOboe.cpp JawnAudioEngineJni.cpp)
target_link_libraries(jawnaudio PRIVATE oboe::oboe android log dl)
