package io.novela.luau

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin/Java wrapper for the native Luau VM State.
 * Exposes explicit native lifecycle methods, JNI-based callback registrations,
 * userdata backing, coroutine resume/yield execution, sandboxing, and module require resolving.
 */
class LuauState private constructor(
    initialHandle: Long,
    private val ownsState: Boolean = true
) : Closeable {

    companion object {
        init {
            try {
                System.loadLibrary("luau")
            } catch (e: UnsatisfiedLinkError) {
                // Expected when running local unit tests; the test class loads the lib manually
            }
        }

        /**
         * Creates and initializes a new Luau VM State with all standard libraries enabled.
         */
        @JvmStatic
        fun createState(): LuauState {
            val handle = nativeCreateState()
            if (handle == 0L) {
                throw LuauException("Failed to initialize native Luau state")
            }
            return LuauState(handle, ownsState = true)
        }

        /**
         * Creates and initializes a new sandboxed Luau VM State with specific capabilities.
         * Opens only the selected libraries and places the VM in read-only sandbox mode.
         */
        @JvmStatic
        fun createState(config: SandboxConfig): LuauState {
            val handle = nativeCreateStateEmpty()
            if (handle == 0L) {
                throw LuauException("Failed to initialize native Luau state")
            }
            val state = LuauState(handle, ownsState = true)
            state.openLibraries(config)
            state.sandbox()
            return state
        }

        /**
         * Creates a temporary LuauState wrapper that does not own the native memory.
         * Used internally for callbacks and threads.
         */
        @JvmStatic
        fun fromHandle(handle: Long): LuauState {
            return LuauState(handle, ownsState = false)
        }

        @JvmStatic
        private external fun nativeCreateState(): Long

        @JvmStatic
        private external fun nativeCreateStateEmpty(): Long

        @JvmStatic
        private external fun nativeCloseState(handle: Long)
    }

    // Thread-safe active handle container
    private val activeHandle = AtomicLong(initialHandle)

    /**
     * Closes the LuauState, destroying the native lua_State and freeing all associated memory.
     * Only triggers native release if this wrapper owns the state.
     */
    override fun close() {
        if (ownsState) {
            val handle = activeHandle.getAndSet(0L)
            if (handle != 0L) {
                nativeCloseState(handle)
            }
        } else {
            activeHandle.set(0L)
        }
    }

    /**
     * Checks whether the state is closed.
     */
    fun isClosed(): Boolean {
        return activeHandle.get() == 0L
    }

    /**
     * Asserts that the state is open, returning the valid native handle.
     * Throws IllegalStateException if it has been closed.
     */
    private fun checkClosed(): Long {
        val handle = activeHandle.get()
        if (handle == 0L) {
            throw IllegalStateException("Attempted to use LuauState after it has been closed")
        }
        return handle
    }

    /**
     * Compiles and executes a Luau script string.
     *
     * @param script The Luau script source code.
     * @throws LuauException If script compilation or execution fails.
     */
    fun execute(script: String) {
        val handle = checkClosed()
        val result = nativeExecute(handle, script)
        if (result != 0) {
            throw LuauException("Luau execution failed with code $result")
        }
    }

    /**
     * Compiles and loads a Luau script onto the stack as a function closure.
     * Does not execute the script.
     *
     * @param script The Luau script source code.
     * @throws LuauException If script compilation or loading fails.
     */
    fun load(script: String) {
        val handle = checkClosed()
        val result = nativeLoad(handle, script)
        if (result != 0) {
            throw LuauException("Luau script loading failed with code $result")
        }
    }

    // --- Stack Size & Navigation Operations ---

    fun getTop(): Int {
        val handle = checkClosed()
        return nativeGetTop(handle)
    }

    fun setTop(idx: Int) {
        val handle = checkClosed()
        nativeSetTop(handle, idx)
    }

    fun pop(n: Int) {
        val handle = checkClosed()
        nativePop(handle, n)
    }

    // --- Value Push Operations ---

    fun pushNil() {
        val handle = checkClosed()
        nativePushNil(handle)
    }

    fun pushBoolean(b: Boolean) {
        val handle = checkClosed()
        nativePushBoolean(handle, b)
    }

    fun pushInteger(i: Int) {
        val handle = checkClosed()
        nativePushInteger(handle, i)
    }

    fun pushNumber(n: Double) {
        val handle = checkClosed()
        nativePushNumber(handle, n)
    }

    fun pushString(s: String) {
        val handle = checkClosed()
        nativePushString(handle, s)
    }

    fun pushByteArray(bytes: ByteArray) {
        val handle = checkClosed()
        nativePushByteArray(handle, bytes)
    }

    fun pushBuffer(bytes: ByteArray) {
        val handle = checkClosed()
        nativePushBuffer(handle, bytes)
    }

    fun pushValue(idx: Int) {
        val handle = checkClosed()
        nativePushValue(handle, idx)
    }

    // --- Type Query Operations ---

    fun type(idx: Int): LuauType {
        val handle = checkClosed()
        val typeVal = nativeType(handle, idx)
        return LuauType.fromOrdinal(typeVal)
    }

    fun isNil(idx: Int): Boolean = type(idx) == LuauType.NIL
    fun isBoolean(idx: Int): Boolean = type(idx) == LuauType.BOOLEAN
    fun isNumber(idx: Int): Boolean = type(idx) == LuauType.NUMBER
    fun isString(idx: Int): Boolean = type(idx) == LuauType.STRING
    fun isTable(idx: Int): Boolean = type(idx) == LuauType.TABLE
    fun isFunction(idx: Int): Boolean = type(idx) == LuauType.FUNCTION
    fun isUserdata(idx: Int): Boolean = type(idx) == LuauType.USERDATA
    fun isThread(idx: Int): Boolean = type(idx) == LuauType.THREAD
    fun isBuffer(idx: Int): Boolean = type(idx) == LuauType.BUFFER

    // --- Value Get/Read Operations ---

    fun getBoolean(idx: Int): Boolean {
        val handle = checkClosed()
        return nativeGetBoolean(handle, idx)
    }

    fun getInteger(idx: Int): Int {
        val handle = checkClosed()
        return nativeGetInteger(handle, idx)
    }

    fun getNumber(idx: Int): Double {
        val handle = checkClosed()
        return nativeGetNumber(handle, idx)
    }

    fun getString(idx: Int): String {
        val handle = checkClosed()
        return nativeGetString(handle, idx) ?: throw LuauException("Value at index $idx is not a string or string-convertible")
    }

    fun getByteArray(idx: Int): ByteArray {
        val handle = checkClosed()
        return nativeGetByteArray(handle, idx) ?: throw LuauException("Value at index $idx is not a string or buffer")
    }

    // --- Table & Global Operations ---

    fun getGlobal(name: String) {
        val handle = checkClosed()
        nativeGetGlobal(handle, name)
    }

    fun setGlobal(name: String) {
        val handle = checkClosed()
        nativeSetGlobal(handle, name)
    }

    fun getField(idx: Int, name: String) {
        val handle = checkClosed()
        nativeGetField(handle, idx, name)
    }

    fun setField(idx: Int, name: String) {
        val handle = checkClosed()
        nativeSetField(handle, idx, name)
    }

    fun rawGet(idx: Int) {
        val handle = checkClosed()
        nativeRawGet(handle, idx)
    }

    fun rawSet(idx: Int) {
        val handle = checkClosed()
        nativeRawSet(handle, idx)
    }

    fun createTable(narr: Int = 0, nrec: Int = 0) {
        val handle = checkClosed()
        nativeCreateTable(handle, narr, nrec)
    }

    fun rawLen(idx: Int): Int {
        val handle = checkClosed()
        return nativeRawLen(handle, idx)
    }

    // --- Stack Manipulation Helpers ---

    fun insert(idx: Int) {
        val handle = checkClosed()
        nativeInsert(handle, idx)
    }

    fun remove(idx: Int) {
        val handle = checkClosed()
        nativeRemove(handle, idx)
    }

    fun replace(idx: Int) {
        val handle = checkClosed()
        nativeReplace(handle, idx)
    }

    // --- Callback, Userdata, and Coroutine APIs ---

    fun pushCallback(callback: LuauCallback) {
        val handle = checkClosed()
        nativePushCallback(handle, callback)
    }

    fun pushUserdata(obj: Any) {
        val handle = checkClosed()
        nativePushUserdata(handle, obj)
    }

    fun getUserdata(idx: Int): Any? {
        val handle = checkClosed()
        return nativeGetUserdata(handle, idx)
    }

    fun newThread(): LuauState {
        val handle = checkClosed()
        val threadHandle = nativeNewThread(handle)
        if (threadHandle == 0L) {
            throw LuauException("Failed to create new Luau thread state")
        }
        return fromHandle(threadHandle)
    }

    fun resume(from: LuauState, narg: Int): Int {
        val handle = checkClosed()
        val fromHandle = from.checkClosed()
        return nativeResume(handle, fromHandle, narg)
    }

    fun yield(nresults: Int): Int {
        throw LuauYieldException(nresults)
    }

    fun status(): Int {
        val handle = checkClosed()
        return nativeStatus(handle)
    }

    fun pcall(nargs: Int, nresults: Int, errfunc: Int = 0): Int {
        val handle = checkClosed()
        return nativePcall(handle, nargs, nresults, errfunc)
    }

    fun collectGarbage() {
        val handle = checkClosed()
        nativeCollectGarbage(handle)
    }

    // --- Phase 4: Sandboxing and Module System APIs ---

    /**
     * Moves values between thread stacks.
     *
     * @param to The target state to move values to.
     * @param n The number of values to move from this state's stack.
     */
    fun xmove(to: LuauState, n: Int) {
        val handle = checkClosed()
        val toHandle = to.checkClosed()
        nativeXmove(handle, toHandle, n)
    }

    /**
     * Opens libraries dynamically based on the capability mask.
     */
    fun openLibraries(config: SandboxConfig) {
        val handle = checkClosed()
        nativeOpenLibraries(handle, config.mask)
    }

    /**
     * Places the VM state in standard read-only sandbox mode.
     */
    fun sandbox() {
        val handle = checkClosed()
        nativeSandbox(handle)
    }

    /**
     * Sandboxes the current thread state, isolating its global environment.
     */
    fun sandboxThread() {
        val handle = checkClosed()
        nativeSandboxThread(handle)
    }

    /**
     * Registers a pluggable module resolver, enabling standard require() support.
     */
    fun setModuleResolver(resolver: LuauModuleResolver) {
        pushCallback(LuauRequireHandler(resolver))
        setGlobal("require")
    }

    // --- Private Native Bridge Functions ---

    private external fun nativeExecute(handle: Long, script: String): Int
    private external fun nativeLoad(handle: Long, script: String): Int
    private external fun nativeGetTop(handle: Long): Int
    private external fun nativeSetTop(handle: Long, idx: Int)
    private external fun nativePop(handle: Long, n: Int)
    private external fun nativePushNil(handle: Long)
    private external fun nativePushBoolean(handle: Long, b: Boolean)
    private external fun nativePushInteger(handle: Long, i: Int)
    private external fun nativePushNumber(handle: Long, n: Double)
    private external fun nativePushString(handle: Long, s: String)
    private external fun nativePushByteArray(handle: Long, bytes: ByteArray)
    private external fun nativePushBuffer(handle: Long, bytes: ByteArray)
    private external fun nativePushValue(handle: Long, idx: Int)
    private external fun nativeType(handle: Long, idx: Int): Int
    private external fun nativeGetBoolean(handle: Long, idx: Int): Boolean
    private external fun nativeGetInteger(handle: Long, idx: Int): Int
    private external fun nativeGetNumber(handle: Long, idx: Int): Double
    private external fun nativeGetString(handle: Long, idx: Int): String?
    private external fun nativeGetByteArray(handle: Long, idx: Int): ByteArray?
    private external fun nativeGetGlobal(handle: Long, name: String)
    private external fun nativeSetGlobal(handle: Long, name: String)
    private external fun nativeGetField(handle: Long, idx: Int, name: String)
    private external fun nativeSetField(handle: Long, idx: Int, name: String)
    private external fun nativeRawGet(handle: Long, idx: Int)
    private external fun nativeRawSet(handle: Long, idx: Int)
    private external fun nativeCreateTable(handle: Long, narr: Int, nrec: Int)
    private external fun nativeRawLen(handle: Long, idx: Int): Int
    private external fun nativeInsert(handle: Long, idx: Int)
    private external fun nativeRemove(handle: Long, idx: Int)
    private external fun nativeReplace(handle: Long, idx: Int)

    // Phase 3 native methods
    private external fun nativePushCallback(handle: Long, callback: LuauCallback)
    private external fun nativePushUserdata(handle: Long, obj: Any)
    private external fun nativeGetUserdata(handle: Long, idx: Int): Any?
    private external fun nativeNewThread(handle: Long): Long
    private external fun nativeResume(handle: Long, fromHandle: Long, narg: Int): Int
    private external fun nativeStatus(handle: Long): Int
    private external fun nativePcall(handle: Long, nargs: Int, nresults: Int, errfunc: Int): Int
    private external fun nativeCollectGarbage(handle: Long)

    // Phase 4 native methods
    private external fun nativeXmove(handle: Long, toHandle: Long, n: Int)
    private external fun nativeOpenLibraries(handle: Long, libsMask: Int)
    private external fun nativeSandbox(handle: Long)
    private external fun nativeSandboxThread(handle: Long)
}
