cmake_minimum_required(VERSION 3.22.1)
project(dancestretch CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(dancestretch SHARED stream_stretcher.cpp stretch_jni.cpp)

target_include_directories(dancestretch PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/signalsmith-linear/include)

# The stretcher runs on the audio thread. Optimise it in debug builds too, or debug builds
# cannot keep up in real time. These flags come after the build type's own -O0.
target_compile_options(dancestretch PRIVATE -O3 -fno-exceptions -fno-rtti -Wall -Wextra)
