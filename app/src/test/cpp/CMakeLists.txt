# Host build of the native stretcher test. Not part of the Gradle build; see stream_stretcher_test.cpp.
cmake_minimum_required(VERSION 3.22.1)
project(stream_stretcher_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MAIN_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_executable(stream_stretcher_test stream_stretcher_test.cpp ${MAIN_CPP}/stream_stretcher.cpp)
target_include_directories(stream_stretcher_test PRIVATE
    ${MAIN_CPP}
    ${MAIN_CPP}/third_party
    ${MAIN_CPP}/third_party/signalsmith-linear/include)
target_compile_options(stream_stretcher_test PRIVATE -O2 -Wall -Wextra)
