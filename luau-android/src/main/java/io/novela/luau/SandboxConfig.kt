package io.novela.luau

/**
 * Capability configuration specifying which standard libraries are enabled in a sandboxed Luau State.
 */
class SandboxConfig private constructor(internal val mask: Int) {

    class Builder {
        private var mask = 0

        fun base() = apply { mask = mask or (1 shl 0) }
        fun coroutine() = apply { mask = mask or (1 shl 1) }
        fun table() = apply { mask = mask or (1 shl 2) }
        fun os() = apply { mask = mask or (1 shl 3) }
        fun string() = apply { mask = mask or (1 shl 4) }
        fun bit32() = apply { mask = mask or (1 shl 5) }
        fun buffer() = apply { mask = mask or (1 shl 6) }
        fun utf8() = apply { mask = mask or (1 shl 7) }
        fun math() = apply { mask = mask or (1 shl 8) }
        fun debug() = apply { mask = mask or (1 shl 9) }
        fun vector() = apply { mask = mask or (1 shl 10) }

        fun all() = apply {
            mask = (1 shl 11) - 1
        }

        fun build(): SandboxConfig = SandboxConfig(mask)
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        fun defaultSafe(): SandboxConfig = Builder()
            .base()
            .coroutine()
            .table()
            .string()
            .bit32()
            .buffer()
            .utf8()
            .math()
            .vector()
            .build()
    }
}
