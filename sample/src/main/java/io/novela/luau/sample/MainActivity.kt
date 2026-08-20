package io.novela.luau.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.novela.luau.LuauState
import io.novela.luau.LuauException

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val tv = TextView(this)
        tv.textSize = 18f
        tv.setPadding(32, 32, 32, 32)
        setContentView(tv)

        try {
            // 1. Create standard non-sandboxed LuauState
            val state = LuauState.createState()
            
            // 2. Register Kotlin callback
            state.pushCallback { s ->
                val name = s.getString(1)
                s.pushString("Hello, $name from Kotlin callback!")
                1
            }
            state.setGlobal("greet")
            
            // 3. Sandbox the state to enforce read-only environment for execution
            state.sandbox()
            
            // 4. Load script onto stack as a closure
            state.load("return greet('Android Developer')")
            
            // 5. Run the loaded script closure (1 result expected, nargs = 0)
            val runStatus = state.pcall(0, 1)
            if (runStatus != 0) {
                val error = state.getString(-1)
                state.pop(1)
                throw LuauException("Script run failed: $error")
            }
            
            // 6. Retrieve result from stack
            val output = state.getString(-1)
            state.pop(1)
            
            state.close()
            
            tv.text = "Luau Script Execution Result:\n\n$output"
        } catch (e: Throwable) {
            tv.text = "Error executing Luau Script:\n\n${e.message}\n\nStacktrace:\n${android.util.Log.getStackTraceString(e)}"
        }
    }
}
