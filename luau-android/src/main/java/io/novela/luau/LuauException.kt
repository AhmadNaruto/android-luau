package io.novela.luau

/**
 * Exception thrown when a compilation or runtime error occurs in the Luau VM.
 */
class LuauException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
