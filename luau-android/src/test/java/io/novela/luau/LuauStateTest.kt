package io.novela.luau

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LuauStateTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            val envLibPath = System.getenv("LUAU_ANDROID_TEST_LIB")
            if (envLibPath != null) {
                val file = File(envLibPath)
                if (file.exists()) {
                    println("Loading libluau.so from LUAU_ANDROID_TEST_LIB: ${file.absolutePath}")
                    System.load(file.absolutePath)
                    return
                }
            }

            val buildDir = File("build")
            var libFile: File? = null
            
            if (buildDir.exists() && buildDir.isDirectory) {
                libFile = buildDir.walkTopDown().firstOrNull { file ->
                    file.isFile && 
                    file.name == "libluau.so" && 
                    file.absolutePath.contains("arm64-v8a") &&
                    !file.absolutePath.contains("intermediates/cxx")
                } ?: buildDir.walkTopDown().firstOrNull { file ->
                    file.isFile && file.name == "libluau.so" && file.absolutePath.contains("arm64-v8a")
                }
            }

            if (libFile != null && libFile.exists()) {
                println("Loading libluau.so from: ${libFile.absolutePath}")
                System.load(libFile.absolutePath)
            } else {
                try {
                    System.loadLibrary("luau")
                } catch (e: UnsatisfiedLinkError) {
                    System.err.println("Could not dynamically load libluau.so from build or system paths: ${e.message}")
                }
            }
        }
    }

    class TestUserdata(val value: Int)

    // --- Phase 2 Regression Tests ---

    @Test
    fun testBasicPushesAndStack() {
        val state = LuauState.createState()
        state.pushBoolean(true)
        assertTrue(state.getBoolean(-1))
        state.close()
    }

    @Test
    fun testTableOperations() {
        val state = LuauState.createState()
        state.createTable(0, 0)
        state.pushString("key")
        state.pushInteger(100)
        state.rawSet(-3)

        state.pushString("key")
        state.rawGet(-2)
        assertEquals(100, state.getInteger(-1))
        state.close()
    }

    // --- Phase 3 Regression Tests ---

    @Test
    fun testLuaToJavaFunctionCall() {
        val state = LuauState.createState()
        val called = AtomicBoolean(false)
        
        state.pushCallback { s ->
            called.set(true)
            assertEquals(10, s.getInteger(1))
            assertEquals("hello", s.getString(2))
            s.pushInteger(42)
            1
        }
        state.setGlobal("javaFunc")
        
        state.execute("local res = javaFunc(10, 'hello'); if res ~= 42 then error('invalid return') end")
        assertTrue(called.get())
        state.close()
    }

    @Test
    fun testJavaToLuaFunctionCall() {
        val state = LuauState.createState()
        state.execute("function luaAdd(a, b) return a + b end")
        state.getGlobal("luaAdd")
        state.pushInteger(15)
        state.pushInteger(25)
        val status = state.pcall(2, 1)
        assertEquals(0, status)
        assertEquals(40, state.getInteger(-1))
        state.close()
    }

    @Test
    fun testLuaToJavaToLuaNestedCall() {
        val state = LuauState.createState()
        state.execute("function luaDouble(x) return x * 2 end")
        state.pushCallback { s ->
            val arg = s.getInteger(1)
            s.getGlobal("luaDouble")
            s.pushInteger(arg)
            s.pcall(1, 1)
            val doubleRes = s.getInteger(-1)
            s.pop(1)
            s.pushInteger(doubleRes + 5)
            1
        }
        state.setGlobal("javaProxy")
        state.execute("local val = javaProxy(10); if val ~= 25 then error('nested failed') end")
        state.close()
    }

    @Test
    fun testJavaCallbackThrowing() {
        val state = LuauState.createState()
        state.pushCallback { _ ->
            throw IllegalStateException("Java custom error message")
        }
        state.setGlobal("throwyFunc")
        try {
            state.execute("throwyFunc()")
            fail("Should have propagated callback exception")
        } catch (e: LuauException) {
            assertTrue(e.message?.contains("Java custom error message") == true)
        }
        state.close()
    }

    private fun helperPushUserdata(state: LuauState): WeakReference<TestUserdata> {
        val dataObj = TestUserdata(999)
        val weakRef = WeakReference(dataObj)
        state.pushUserdata(dataObj)
        state.setGlobal("MY_USERDATA")
        return weakRef
    }

    @Test
    fun testUserdataGarbageCollection() {
        val state = LuauState.createState()
        val weakRef = helperPushUserdata(state)
        
        System.gc()
        System.runFinalization()
        assertNotNull(weakRef.get())
        
        state.execute("MY_USERDATA = nil")
        state.setTop(0)
        state.collectGarbage()
        state.collectGarbage()
        
        for (i in 1..10) {
            System.gc()
            System.runFinalization()
            if (weakRef.get() == null) break
            Thread.sleep(20)
        }
        assertNull(weakRef.get())
        state.close()
    }

    @Test
    fun testCoroutineYieldAndResume() {
        val state = LuauState.createState()
        val threadState = state.newThread()
        
        state.pushCallback { s ->
            s.pushInteger(100)
            s.yield(1)
        }
        state.setGlobal("yieldyFunc")
        
        threadState.load("local val = yieldyFunc(); return val + 50")
        val status = threadState.resume(state, 0)
        assertEquals(1, status)
        assertEquals(100, threadState.getInteger(-1))
        
        val finalStatus = threadState.resume(state, 1)
        assertEquals(0, finalStatus)
        assertEquals(150, threadState.getInteger(-1))
        state.close()
    }

    // --- Phase 4 Capabilities, Sandboxing & Module Resolver Tests ---

    // 1. Sandbox Isolation
    @Test
    fun testSandboxIsolation() {
        val config = SandboxConfig.builder().base().math().build()
        val state = LuauState.createState(config)
        
        // In Luau's sandbox, modifying read-only globals like math or string tables should throw an error
        try {
            state.execute("math.sin = function() return 0 end")
            fail("Should have blocked global library modification in sandbox")
        } catch (e: LuauException) {
            // Expected - table is read-only
        }
        
        // Modifying _G in sandboxed environments should fail or be local to the thread
        try {
            state.execute("string.upper = nil")
            fail("Should have blocked core global table modification")
        } catch (e: LuauException) {
            // Expected
        }
        
        state.close()
    }

    // 2. Standard library availability
    @Test
    fun testStandardLibraryAvailability() {
        val config = SandboxConfig.builder().base().math().build()
        val state = LuauState.createState(config)
        
        // Verify math is present
        state.execute("local x = math.sqrt(100); if x ~= 10 then error('math library broken') end")
        
        state.close()
    }

    // 3. Disabled libraries
    @Test
    fun testDisabledLibraries() {
        val config = SandboxConfig.builder().base().math().build()
        val state = LuauState.createState(config)
        
        try {
            state.execute("local x = string; if x == nil then error('string is nil') end")
            fail("Should have failed as string library is not loaded")
        } catch (e: LuauException) {
            assertTrue(e.message?.contains("string is nil") == true)
        }
        
        state.close()
    }

    // 4 & 5. Module loading and caching
    @Test
    fun testModuleLoadingAndCaching() {
        val state = LuauState.createState()
        val moduleLoads = AtomicInteger(0)
        
        val modules = mapOf(
            "my_module" to """
                local M = {}
                M.value = 100
                return M
            """
        )
        
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? {
                moduleLoads.incrementAndGet()
                return modules[moduleName]
            }
        })
        
        state.execute("""
            local m1 = require('my_module')
            local m2 = require('my_module')
            if m1.value ~= 100 or m2.value ~= 100 or m1 ~= m2 then
                error('module loading or caching broken')
            end
        """)
        
        // Should only be resolved and executed ONCE
        assertEquals(1, moduleLoads.get())
        state.close()
    }

    // 6. Nested require
    @Test
    fun testNestedRequire() {
        val state = LuauState.createState()
        val modules = mapOf(
            "math_utils" to "return { add = function(a, b) return a + b end }",
            "calculator" to """
                local utils = require('math_utils')
                return {
                    calculate = function(x, y)
                        return utils.add(x, y) * 2
                    end
                }
            """
        )
        
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = modules[moduleName]
        })
        
        state.execute("""
            local calc = require('calculator')
            local res = calc.calculate(5, 10)
            if res ~= 30 then
                error('nested require execution failed: got ' .. tostring(res))
            end
        """)
        state.close()
    }

    // 7. Cyclic require
    @Test
    fun testCyclicRequire() {
        val state = LuauState.createState()
        val modules = mapOf(
            "A" to "local B = require('B'); return { val = 'A' }",
            "B" to "local A = require('A'); return { val = 'B' }"
        )
        
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = modules[moduleName]
        })
        
        try {
            state.execute("require('A')")
            fail("Should have thrown error on cyclic dependency")
        } catch (e: LuauException) {
            assertTrue(e.message?.contains("Cyclic dependency detected") == true)
        }
        
        state.close()
    }

    // 8. Missing module
    @Test
    fun testMissingModule() {
        val state = LuauState.createState()
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = null
        })
        
        try {
            state.execute("require('unknown_mod')")
            fail("Should have failed for missing module")
        } catch (e: LuauException) {
            assertTrue(e.message?.contains("Module 'unknown_mod' not found") == true)
        }
        
        state.close()
    }

    // 9. Compile failure in module
    @Test
    fun testCompileFailureInModule() {
        val state = LuauState.createState()
        val modules = mapOf(
            "bad_syntax" to "if true then"
        )
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = modules[moduleName]
        })
        
        try {
            state.execute("require('bad_syntax')")
            fail("Should have failed to compile module")
        } catch (e: LuauException) {
            // Expected
        }
        
        state.close()
    }

    // 10. Runtime failure in module
    @Test
    fun testRuntimeFailureInModule() {
        val state = LuauState.createState()
        val modules = mapOf(
            "bad_run" to "error('runtime crash inside module')"
        )
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = modules[moduleName]
        })
        
        try {
            state.execute("require('bad_run')")
            fail("Should have thrown execution failure")
        } catch (e: LuauException) {
            assertTrue(e.message?.contains("runtime crash inside module") == true)
        }
        
        state.close()
    }

    // 11. Malicious module names (escaping boundaries)
    @Test
    fun testMaliciousModuleNames() {
        val state = LuauState.createState()
        val resolvedName = java.util.concurrent.atomic.AtomicReference<String>()
        
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? {
                // Assert resolver receives exact name and does not treat path sequences as special
                resolvedName.set(moduleName)
                return "return 'safe'"
            }
        })
        
        state.execute("require('../../../etc/passwd')")
        assertEquals("../../../etc/passwd", resolvedName.get())
        
        state.close()
    }

    // 12. VM destruction with loaded modules
    @Test
    fun testVmDestructionWithLoadedModules() {
        val state = LuauState.createState()
        val modules = mapOf(
            "temp_module" to "return { val = 123 }"
        )
        state.setModuleResolver(object : LuauModuleResolver {
            override fun resolve(moduleName: String): String? = modules[moduleName]
        })
        
        state.execute("require('temp_module')")
        state.close() // Releasing VM with all modules inside registry cache
    }

    // --- Interpreter Baseline Performance Benchmark ---
    @Test
    fun testInterpreterPerformance() {
        val startInit = System.nanoTime()
        val state = LuauState.createState()
        val endInit = System.nanoTime()
        
        val startupTimeMs = (endInit - startInit) / 1_000_000.0
        
        val fibScript = """
            local function fib(n)
                if n < 2 then return n end
                return fib(n - 1) + fib(n - 2)
            end
            return fib(25)
        """
        
        state.load(fibScript)
        
        val startExec = System.nanoTime()
        val status = state.pcall(0, 1)
        val endExec = System.nanoTime()
        
        val execTimeMs = (endExec - startExec) / 1_000_000.0
        val resultVal = state.getInteger(-1)
        state.pop(1)
        
        assertEquals(75025, resultVal)
        assertEquals(0, status)
        
        println("=== Interpreter Performance Baseline ===")
        println("VM Startup Time: $startupTimeMs ms")
        println("Fibonacci(25) Execution Time: $execTimeMs ms")
        println("=========================================")
        
        state.close()
    }

    @Test
    fun testBufferOperations() {
        val state = LuauState.createState()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        state.pushBuffer(data)
        
        assertEquals(LuauType.BUFFER, state.type(-1))
        
        val readBack = state.getByteArray(-1)
        assertArrayEquals(data, readBack)
        
        state.close()
    }

    @Test
    fun testVectorType() {
        val state = LuauState.createState()
        state.execute("result = vector.create(1, 2.5, 3)")
        state.getGlobal("result")
        
        assertEquals(LuauType.VECTOR, state.type(-1))
        state.pop(1)
        state.close()
    }

    @Test
    fun testVmCreationStress() {
        for (i in 1..200) {
            val state = LuauState.createState()
            state.execute("local x = $i")
            state.close()
        }
    }

    @Test
    fun testStackOperationsStress() {
        val state = LuauState.createState()
        for (i in 1..100_000) {
            state.pushInteger(i)
            state.pop(1)
        }
        state.close()
    }

    @Test
    fun testFuzzCompilerInput() {
        val state = LuauState.createState()
        val random = java.util.Random(12345)
        for (i in 1..200) {
            val length = random.nextInt(50) + 1
            val bytes = ByteArray(length)
            random.nextBytes(bytes)
            val fuzzString = String(bytes, Charsets.ISO_8859_1)
            try {
                state.execute(fuzzString)
            } catch (e: LuauException) {
                // Expected compiler/execution error
            }
        }
        state.close()
    }

    @Test
    fun testMalformedBytecode() {
        val state = LuauState.createState()
        val badBytecode = "\u001bLuau\u0001\u0002\u0003garbage"
        try {
            state.load(badBytecode)
            fail("Should have failed to load malformed bytecode")
        } catch (e: LuauException) {
            // Expected
        }
        state.close()
    }
}
