package io.novela.luau

/**
 * Exception thrown by a [LuauCallback] to signal to the JNI layer
 * that the current coroutine should yield.
 */
class LuauYieldException(val nresults: Int) : RuntimeException("Yielding coroutine execution")
