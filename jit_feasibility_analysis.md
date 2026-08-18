# Technical Feasibility Analysis: Luau CodeGen/JIT on Android

Before enabling the Luau CodeGen (JIT compilation) module, we present a feasibility analysis evaluating the security, compatibility, and overhead constraints of compiling and running runtime-generated machine code on Android devices.

---

## 1. Core Technical Feasibility

### Android ARM64 Target
*   **Status**: Technically Compatible.
*   **Details**: Luau's CodeGen module provides a native A64 assembler (targeting 64-bit ARM architecture) that aligns with the `arm64-v8a` ABI constraints of our Android target.

### Executable Memory & Permissions (W^X Constraints)
*   **Status**: Strictly Blocked.
*   **Details**: Operating systems enforce W^X (Write XOR Execute) memory protections. In Luau CodeGen, this is implemented in `CodeAllocator.cpp` using anonymous `mmap` with `PROT_READ | PROT_WRITE` permissions, writing machine instructions to the pages, and then attempting to swap page permissions to `PROT_READ | PROT_EXEC` using `mprotect()`. 

### SELinux Implications on Modern Android (API 29+)
*   **Status**: Strictly Blocked (Permission Denied).
*   **Details**: Starting with Android 10 (API Level 29) and above, Android's SELinux policy enforces strict limitations on untrusted apps (which include standard user-installed applications). 
    *   SELinux denies the `execmem` permission for untrusted apps.
    *   Any call to `mprotect()` requesting `PROT_EXEC` on anonymous memory pages will fail, returning `-1` with `errno = EACCES` or `EPERM`.
    *   Although Luau handles allocator failure gracefully by refusing to register JIT hooks and falling back to interpreter mode, JIT will **never** be able to compile or run code on any modern retail Android device.

### Cache Synchronization
*   **Status**: Feasible (but moot due to permissions).
*   **Details**: On ARM64, the Instruction Cache (I-cache) is not automatically coherent with the Data Cache (D-cache) when code is generated dynamically. The CPU must flush the dirty data lines to memory and invalidate the corresponding instruction cache lines before executing the generated code. Luau implements this correctly using `__builtin___clear_cache()`, which would work if the memory page was marked executable.

---

## 2. Resource & Overhead Impact

### Binary & APK Size
*   **Status**: High Overhead.
*   **Details**: Upstream Luau's CodeGen module incorporates target-specific assemblers (A64, X64), intermediate representation (IR) builders, register allocators, code generators, and unwind information builders. Enabling CodeGen in the build configuration:
    *   Increases the size of `libluau.so` by roughly **350KB to 500KB** (a ~70% size penalty on the native runtime library).
    *   This directly inflates the final APK/AAB size for consumer application developers, for a feature that is blocked by the OS.

### Startup Cost & Memory Consumption
*   **Status**: Medium Overhead.
*   **Details**: JIT compilation requires allocating virtual memory blocks in minimum sizes of 1 system page (typically 4KB or 16KB on Android devices). It also introduces compile-time latency (warmup) to parse bytecode and compile it to native machine instructions, increasing startup time and memory overhead.

---

## 3. Security Implications

*   **JIT Exploits**: Executable memory pages are a high-value target for security exploits. If an attacker can inject custom bytecodes or bytecode configurations, writable/executable memory pages can be used for JIT-spraying or execute shellcode payloads.
*   **Interpretation Posture**: Keeping JIT disabled enforces standard interpretation, which does not require writing to executable memory pages. This aligns with Android secure-by-default architecture guidelines.

---

## 4. Android Version Compatibility Summary

| Android Version | API Level | JIT / CodeGen Status | Reason |
| :--- | :--- | :--- | :--- |
| **Android 9.0 and below** | API <= 28 | Mixed / Allowed | SELinux `execmem` permissions are less strict; anonymous `mprotect(PROT_EXEC)` may succeed depending on OEM security configurations. |
| **Android 10.0+** | API >= 29 | **Strictly Blocked** | Android SELinux enforces strict W^X on anonymous memory for untrusted app domains. |

---

## 5. Final Decision & Recommendation

> [!IMPORTANT]
> **Decision: Leave Luau CodeGen/JIT Disabled on Android.**
> 
> **Rationale**:
> 1. **Zero Practical Benefit**: Due to SELinux `execmem` policy blocks, JIT compilation is completely blocked on all modern Android versions (Android 10+). It will always fall back to the interpreter.
> 2. **Substantial Size Penalty**: Adding CodeGen adds ~70% size overhead to the native library, increasing the APK size for all consumer applications.
> 3. **Reduced Attack Surface**: Keeping memory pages non-executable provides the highest security footprint.
> 4. **Interpreter Performance**: The Luau interpreter is highly optimized and on-par with or faster than JIT overhead for short-lived script execution blocks.
