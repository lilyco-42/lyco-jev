# lyco-jev on-device llama.cpp scorer (goal 1).
# Pinned to the revision the Jev-Style release notes were tested against; Qwen3.5
# support and llama_context_params.n_outputs_max both land in it.
cmake_minimum_required(VERSION 3.22.1)
project(jevlocal CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG 441df11f65ea0b6d0c72965aaf70c8241070ddcb
        GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(llama)

find_library(log-lib log)

add_library(jevjni SHARED jev_jni.cpp)
target_link_libraries(jevjni PRIVATE llama ${log-lib})
