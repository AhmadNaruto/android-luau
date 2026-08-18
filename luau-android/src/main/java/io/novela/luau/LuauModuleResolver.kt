package io.novela.luau

/**
 * Pluggable interface for resolving module source code during require() calls.
 */
interface LuauModuleResolver {
    /**
     * Resolves a module identifier to its Luau source script.
     *
     * @param moduleName The identifier/path of the module to resolve.
     * @return The Luau source string, or null if the module cannot be found.
     * @throws Exception If resolution fails due to a custom error.
     */
    fun resolve(moduleName: String): String?
}
