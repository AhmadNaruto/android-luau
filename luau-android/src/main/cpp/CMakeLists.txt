cmake_minimum_required(VERSION 3.22.1)

project("luau-jni" LANGUAGES C CXX)

# Require C++17 as mandated by upstream Luau
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable unnecessary upstream targets for our Android library
set(LUAU_BUILD_CLI OFF CACHE BOOL "Build Luau CLI" FORCE)
set(LUAU_BUILD_TESTS OFF CACHE BOOL "Build Luau Tests" FORCE)
set(LUAU_BUILD_CODEGEN OFF CACHE BOOL "Build Luau CodeGen" FORCE)

# Add upstream Luau subdirectory
add_subdirectory(luau)

# Define our JNI library target
add_library(luau SHARED
    luau-bridge.cpp
)

# Header search paths for compiling the bridge
target_include_directories(luau PRIVATE
    luau/VM/include
    luau/Compiler/include
    luau/Ast/include
    luau/Common/include
)

# Link against the static Luau libraries and Android logging
target_link_libraries(luau PRIVATE
    Luau.VM
    Luau.Compiler
    Luau.Ast
    log
)
