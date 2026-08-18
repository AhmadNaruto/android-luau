cmake_minimum_required(VERSION 3.22.1)

project("luau-jni" LANGUAGES C CXX)

# Enforce Position Independent Code (required for JNI shared libraries and static linkage)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Require C++17 as mandated by upstream Luau
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Disable unnecessary upstream targets for our Android library
set(LUAU_BUILD_CLI OFF CACHE BOOL "Build Luau CLI" FORCE)
set(LUAU_BUILD_TESTS OFF CACHE BOOL "Build Luau Tests" FORCE)
set(LUAU_BUILD_CODEGEN OFF CACHE BOOL "Build Luau CodeGen" FORCE)

# Add upstream Luau subdirectory
add_subdirectory(luau)

# Exclude unused upstream static libraries from the default build to optimize compilation times
set_target_properties(Luau.Analysis PROPERTIES EXCLUDE_FROM_ALL ON)
set_target_properties(Luau.Require PROPERTIES EXCLUDE_FROM_ALL ON)
set_target_properties(Luau.CLI.lib PROPERTIES EXCLUDE_FROM_ALL ON)
set_target_properties(Luau.Config PROPERTIES EXCLUDE_FROM_ALL ON)
set_target_properties(isocline PROPERTIES EXCLUDE_FROM_ALL ON)
if(TARGET Luau.CodeGen)
    set_target_properties(Luau.CodeGen PROPERTIES EXCLUDE_FROM_ALL ON)
endif()


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

if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(luau PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Link against the static Luau libraries and Android logging (if compiling for Android)
if(ANDROID)
    target_link_libraries(luau PRIVATE
        Luau.VM
        Luau.Compiler
        Luau.Ast
        log
    )
else()
    target_link_libraries(luau PRIVATE
        Luau.VM
        Luau.Compiler
        Luau.Ast
    )
endif()
