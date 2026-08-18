package io.novela.luau

/**
 * Handles executing and caching standard require() module dependencies inside Luau.
 * Uses Lua-side tables (_MODULE_CACHE, _MODULE_LOADING) to track loads and detect cyclic dependencies.
 */
class LuauRequireHandler(
    private val resolver: LuauModuleResolver
) : LuauCallback {

    override fun invoke(state: LuauState): Int {
        val s = state
        val moduleName = s.getString(1)

        // 1. Get or create cache table
        s.getGlobal("_MODULE_CACHE")
        if (s.isNil(-1)) {
            s.pop(1)
            s.createTable(0, 0)
            s.pushValue(-1)
            s.setGlobal("_MODULE_CACHE")
        }

        // Check if loaded in cache
        s.pushString(moduleName)
        s.rawGet(-2) // Stack: [cache_table, cache_value]
        if (!s.isNil(-1)) {
            s.remove(-2) // Remove cache table. Stack: [cache_value]
            return 1
        }
        s.pop(1) // Pop nil

        // 2. Get or create loading table (cyclic dependency check)
        s.getGlobal("_MODULE_LOADING")
        if (s.isNil(-1)) {
            s.pop(1)
            s.createTable(0, 0)
            s.pushValue(-1)
            s.setGlobal("_MODULE_LOADING")
        }

        // Check if currently loading
        s.pushString(moduleName)
        s.rawGet(-2) // Stack: [cache_table, loading_table, is_loading]
        if (s.getBoolean(-1)) {
            s.pop(3) // Clear stack (is_loading, loading_table, cache_table)
            throw LuauException("Cyclic dependency detected: module '$moduleName' is already loading")
        }
        s.pop(1) // Pop false/nil

        // Mark as loading
        s.pushString(moduleName)
        s.pushBoolean(true)
        s.rawSet(-3) // loading_table[moduleName] = true

        // 3. Resolve the module source code
        val source = try {
            resolver.resolve(moduleName)
        } catch (e: Exception) {
            // Clear loading flag
            s.pushString(moduleName)
            s.pushNil()
            s.rawSet(-3) // loading_table[moduleName] = nil
            s.pop(2) // Clear cache and loading tables
            throw LuauException("Error resolving module '$moduleName': ${e.message}", e)
        }

        if (source == null) {
            // Clear loading flag
            s.pushString(moduleName)
            s.pushNil()
            s.rawSet(-3) // loading_table[moduleName] = nil
            s.pop(2) // Clear cache and loading tables
            throw LuauException("Module '$moduleName' not found")
        }

        // 4. Create new coroutine (thread) for execution
        val threadState = s.newThread() // Stack: [cache_table, loading_table, thread]

        try {
            threadState.load(source)

            // Resume thread to execute script
            val resumeStatus = threadState.resume(s, 0)
            if (resumeStatus != 0) {
                // If it yielded or failed with execution status
                throw LuauException("Module '$moduleName' failed to execute or yielded unexpectedly")
            }

            val nresults = threadState.getTop()
            if (nresults > 0) {
                // Move the top result from threadState to s
                threadState.xmove(s, 1) // Stack: [cache_table, loading_table, thread, module_value]
            } else {
                s.pushNil()
            }

            // Cache the result
            s.pushString(moduleName)
            s.pushValue(-2) // Duplicate module_value
            s.rawSet(-6) // cache_table[moduleName] = module_value

            // Clean up: Clear loading status
            s.pushString(moduleName)
            s.pushNil()
            s.rawSet(-5) // loading_table[moduleName] = nil

            // Stack contains moduleName (at -5), cache_table (at -4), loading_table (at -3), thread (at -2), module_value (at -1)
            s.replace(-5) // Replace moduleName at bottom with module_value
            s.pop(3) // Pop cache_table, loading_table, threadState

            return 1
        } catch (e: Exception) {
            // Clear loading flag
            s.pushString(moduleName)
            s.pushNil()
            s.rawSet(-4) // loading_table[moduleName] = nil
            s.pop(3) // Clear tables and thread
            throw e
        }
    }
}
