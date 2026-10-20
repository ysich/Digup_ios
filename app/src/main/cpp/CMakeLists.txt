cmake_minimum_required(VERSION 3.22.1)
project(digup_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp is missing. Run scripts/fetch-llama.ps1 (Windows) or scripts/fetch-llama.sh first.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(digup_llama SHARED digup_llama.cpp)
target_include_directories(digup_llama PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/tools/mtmd")
target_compile_options(digup_llama PRIVATE -O3 -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(digup_llama PRIVATE -Wl,--gc-sections)
target_link_libraries(digup_llama PRIVATE
    mtmd
    llama
    ggml
    android
    log
    jnigraphics)
