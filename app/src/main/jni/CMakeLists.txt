cmake_minimum_required(VERSION 3.18)
project("obfuscation_native" C)

# Library name must match System.loadLibrary("obfuscation_native")
add_library(obfuscation_native SHARED
    aes.c
    obfuscation_jni.c
)

# The NDK's CMake toolchain file sets up JNI include paths automatically.
# Do NOT use find_package(JNI) — it searches for desktop JVM paths and
# will fail in the Android NDK environment.
#
# JNI headers (<jni.h>) are at ${ANDROID_NDK}/sysroot/usr/include
# and are already on the implicit include path.

# Optimize for size — the entire library is ~3 KB
target_compile_options(obfuscation_native PRIVATE
    -Os
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

# Strip unused sections at link time
target_link_options(obfuscation_native PRIVATE
    -Wl,--gc-sections
    -s
)

# C standard
set_target_properties(obfuscation_native PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
)
