package io.novela.luau

/**
 * Functional interface for callbacks registered from Kotlin/Java to Luau.
 */
fun interface LuauCallback {
    /**
     * Invoked when the callback is called from the Luau VM.
     *
     * @param state The LuauState wrapper representing the thread/state in which the execution is taking place.
     * @return The number of values pushed onto the stack as return values.
     * @throws LuauYieldException To yield execution back to the caller.
     */
    fun invoke(state: LuauState): Int
}
