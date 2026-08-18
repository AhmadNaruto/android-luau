package io.novela.luau

/**
 * Enumeration representing Luau VM data types.
 */
enum class LuauType {
    NONE,
    NIL,
    BOOLEAN,
    LIGHTUSERDATA,
    NUMBER,
    INTEGER,
    VECTOR,
    STRING,
    TABLE,
    FUNCTION,
    USERDATA,
    THREAD,
    BUFFER;

    companion object {
        private val VALUES = values()

        /**
         * Resolves the enum from a JNI ordinal mapping.
         */
        fun fromOrdinal(ordinal: Int): LuauType {
            if (ordinal < 0 || ordinal >= VALUES.size) {
                return NONE
            }
            return VALUES[ordinal]
        }
    }
}
