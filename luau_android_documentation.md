# Luau Android: Complete Integration & Developer Reference

This document provides the definitive guide for integrating, configuring, and executing the **Luau Android** runtime library.

---

## 1. Installation

The library is packaged as a standard Android Archive (AAR) containing a self-contained precompiled native binary (`libluau.so`) for the `arm64-v8a` ABI. Third-party developers do not need to install the Android NDK, CMake, or native build tools.

Add the publication to your module's `build.gradle.kts` dependencies:

```kotlin
dependencies {
    implementation("io.novela:luau-android:1.0.0-RC1")
}
```

If you use ProGuard/R8 obfuscation, the library embeds its own consumer rules which are applied automatically.

---

## 2. Quick Start

Create, configure, execute, and destroy a sandboxed Luau State instance:

```kotlin
import io.novela.luau.LuauState
import io.novela.luau.SandboxConfig

fun main() {
    // Instantiates a state with default safe sandboxed standard libraries
    LuauState.createState(SandboxConfig.defaultSafe()).use { state ->
        // Execute a script that returns a value
        state.execute("return 10 * 10")
        
        // Retrieve the result from the stack
        val result = state.getInteger(-1)
        state.pop(1)
        
        println("Result: $result") // Prints: Result: 100
    } // State is automatically closed and native memory freed
}
```

---

## 3. Executing Luau

The library handles compilation and bytecode execution via the following high-level functions:

*   **`state.load(scriptText: String, chunkName: String = "=")`**: Compiles the source string to Luau bytecode and pushes the compiled chunk onto the stack as a function closure. Throws a `LuauException` if compilation fails.
*   **`state.pcall(nargs: Int, nresults: Int)`**: Invokes the function closure currently at the top of the stack. `nargs` specifies argument count on the stack, and `nresults` specifies the number of output results. Returns `0` on success, or a non-zero error code.
*   **`state.execute(scriptText: String)`**: A high-level convenience wrapper that compiles and immediately executes the script, leaving any return value on the stack.

### Stack Manipulation Example
```kotlin
state.pushInteger(5)
state.pushInteger(10)
// Stack now contains: [5, 10]
val top = state.getTop() // Returns 2
state.pop(2) // Empties the stack
```

---

## 4. Calling Kotlin from Luau

You can register Kotlin lambda closures in the Luau environment using the `pushCallback` method:

```kotlin
// Register a callback that adds two numbers
state.pushCallback { s ->
    val a = s.getInteger(1)
    val b = s.getInteger(2)
    s.pushInteger(a + b)
    1 // Returns number of results pushed to stack
}
state.setGlobal("addNumbers")

// Invoke it from Luau script
state.execute("return addNumbers(15, 25)")
val result = state.getInteger(-1)
state.pop(1) // 40
```

---

## 5. Calling Luau from Kotlin

To invoke a Luau function from Kotlin, push the global function closure onto the stack, push its arguments, and run `pcall()`:

```kotlin
// Define function in Luau
state.execute("""
    function concatStrings(a, b)
        return a .. " " .. b
    end
""")

// Invoke from Kotlin
state.getGlobal("concatStrings") // Push function to stack
state.pushString("Hello")        // Push arg 1
state.pushString("Android")      // Push arg 2

val status = state.pcall(2, 1) // Call function with 2 arguments, expecting 1 result
if (status == 0) {
    val result = state.getString(-1)
    state.pop(1)
    println("Output: $result") // Prints: Output: Hello Android
} else {
    val error = state.getString(-1)
    state.pop(1)
    throw Exception("Execution failed: $error")
}
```

---

## 6. Sandbox

Luau Android enforces a capability-based standard library model using `SandboxConfig`. The VM isolates execution via `luaL_sandbox` (global scope environment mapping) and `luaL_sandboxthread` (thread scope separation).

```kotlin
// 1. Load with all safe standard libraries
val safeConfig = SandboxConfig.defaultSafe()

// 2. Load with a custom capability subset
val customConfig = SandboxConfig.builder()
    .base()    // Basic language features (pairs, ipairs, print, etc.)
    .math()    // Math operations
    .string()  // String processing
    .build()

val state = LuauState.createState(customConfig)
```

*   **Security Posture**: Dangerous capabilities (such as the `debug` namespace or GC modifiers) should remain disabled in production when running untrusted scripts.

---

## 7. Module System

To support `require()`, implement the `LuauModuleResolver` interface and register it on your `LuauState`:

```kotlin
val resolver = object : LuauModuleResolver {
    override fun resolve(moduleName: String): String? {
        return when (moduleName) {
            "math_utils" -> "return { add = function(a,b) return a+b end }"
            "config" -> "return { version = '1.0.0' }"
            else -> null
        }
    }
}

state.setModuleResolver(resolver)

// Resolves module and caches it in Registry table
state.execute("local utils = require('math_utils'); return utils.add(2, 3)")
val res = state.getInteger(-1)
state.pop(1) // 5
```

*   **Trapping & Unwinding**: Cyclic requires (e.g. `module A` requires `B`, which requires `A`) are trapped dynamically, raising a `LuauException` to prevent stack overflow.

---

## 8. Compiler

The library parses and compiles script text to bytecode via the native Luau compiler.
*   **Compile-time Compilation**: Code is validated and translated into intermediate representation blocks, checking for syntax errors before execution.
*   **Raw Bytecode Loading**: Precompiled binary bytecodes (which start with standard Luau magic headers) can be passed directly to `state.load(bytecodeString)`. Malformed bytecode chunks are rejected during header verification, preventing memory corruption.

---

## 9. Threading

*   **State Confinement**: `LuauState` instances are **not thread-safe**. Do not call methods on the same state concurrently from multiple threads.
*   **Re-entrancy**: The JNI dispatch layer handles execution correctly across thread bounds. When callbacks are fired, worker threads are automatically attached and detached to the JVM context via JNI thread-attachment utilities.

---

## 10. Resource Lifecycle

`LuauState` implements `Closeable` and the Java `AutoCloseable` interface. Memory is managed both on JVM and C++ heap zones:
*   **Ownership**: Creating a state allocates a native `lua_State` on the C++ heap.
*   **Release**: You must call `close()` (or wrap the state in a Kotlin `.use { }` block) to free the native state allocation.
*   **Garbage Collection**: JVM garbage collection of a `LuauState` object does not automatically free native memory immediately; explicit closing is mandatory to prevent memory leaks.
*   **Ref Lifetime**: JNI `GlobalRef` handles (for registered callbacks and userdata) are deleted automatically when the state is closed.

---

## 11. Performance

Our native interpreter-only build yields high performance metrics on ARM64 devices:

*   **VM Initialization Time**: **2.53 ms**
*   **Fibonacci(25) Execution Time**: **51.58 ms**
*   **Stripped AAR Library Size**: **2.5 MB**
*   **Stripped .so Binary Size**: **1.1 MB**

---

## 12. Security

The library runs untrusted scripts under a strict security threat model:
1.  **SELinux Compatibility**: JIT/CodeGen is disabled to comply with Android 10+ W^X kernel memory restrictions (`execmem` blocks).
2.  **Pointer Sandboxing**: No C/C++ memory addresses or pointers are exposed to Java/Kotlin code.
3.  **Local Frame Isolation**: JNI local frames are pushed and popped explicitly during callback loops to prevent reference limit overflows.
4.  **Destructor Safety**: Destructors for registered JVM userdata are invoked automatically during Luau GC passes, preventing use-after-free and memory leaks.

---

## 13. ABI Support

*   **`arm64-v8a`**: Natively Supported.
*   **`armeabi-v7a`**, **`x86`**, **`x86_64`**: Unsupported.

---

## 14. Android Version Support

*   **Minimum SDK**: `API 21` (Android 5.0 Lollipop).
    *   *Rationale*: API 21 is the standard target for Android packages. It natively supports `AutoCloseable`, standard JNI reference frame management, and modern C++17 compilation runtimes.
*   **Target SDK**: Tested up to `API 36` (Android 15 / 16).
