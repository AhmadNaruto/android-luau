# Keep the native methods and JNI class targets
-keep class io.novela.luau.LuauState {
    native <methods>;
    *** nativeCreateState(...);
    *** nativeCreateStateEmpty(...);
    *** nativeCloseState(...);
}

# Keep the companion object class to avoid mangling its static/JvmStatic methods accessed by JNI
-keep class io.novela.luau.LuauState$Companion {
    *;
}

# Keep the LuauCallback functional interface and its implementations
-keep interface io.novela.luau.LuauCallback {
    *;
}

# Keep exceptions that are thrown from JNI/C++ code
-keep class io.novela.luau.LuauException {
    <init>(...);
}
-keep class io.novela.luau.LuauYieldException {
    <init>(...);
    *;
}

# Keep LuauType enum fields
-keep class io.novela.luau.LuauType {
    *;
}
