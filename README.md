# Luau Android

A reusable Android library that embeds the [Luau scripting language](https://luau.org/) into Kotlin/Java applications using JNI.

---

## Features

*   **JNI Bridging**: Lightweight and robust JNI wrapper around the native Luau VM, omitting FFM and JExtract dependencies.
*   **Callback Dispatching**: Register Kotlin/Java closures in the Luau VM with automatic thread attachment and local reference frame management.
*   **Capability Sandboxing**: Control which standard library namespaces (math, table, string, buffer, vector) are exposed to threads.
*   **Module System**: Pluggable resolvers for `require()` with cyclic dependency trapping and safe stack unwinding.
*   **Self-Contained AAR**: Precompiled native binaries for the `arm64-v8a` ABI packaged in the AAR, statically linked with `c++_static` to prevent external loading issues.

---

## Documentation

Detailed integration guides, feasibility analyses, and readiness reports are stored in the root directory:

1.  **[Developer Integration Guide](luau_android_documentation.md)**: Setup guides, API classifications, sandboxing controls, threading semantics, resource lifecycles, and ProGuard rules.
2.  **[JIT Feasibility Analysis](jit_feasibility_analysis.md)**: Technical breakdown of Android SELinux `execmem` blocks, W^X memory restrictions on API 29+, cache synchronization, and APK footprints.
3.  **[Production Readiness Report](production_readiness_report.md)**: Threat models, compiler fuzzing, VM stress-testing statistics, comparison gap analysis, and release candidate configurations.

---

## Installation

Add the Maven publication to your module's `build.gradle.kts` dependencies:

```kotlin
dependencies {
    implementation("io.novela:luau-android:1.0.0-RC1")
}
```

Refer to the **[Quick Start Guide](luau_android_documentation.md#2-quick-start)** to begin executing scripts.

---

## License

This project is licensed under the MIT License.
