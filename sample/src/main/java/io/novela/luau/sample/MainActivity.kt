package io.novela.luau.sample

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.novela.luau.LuauState
import io.novela.luau.SandboxConfig

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val tv = TextView(this)
        tv.textSize = 18f
        tv.setPadding(32, 32, 32, 32)
        setContentView(tv)

        try {
            // Instantiate Luau State with default safe configurations (sandboxed)
            val state = LuauState.createState(SandboxConfig.defaultSafe())
            
            // Register a Kotlin callback
            state.pushCallback { s ->
                val name = s.getString(1)
                s.pushString("Hello, $name from Kotlin callback!")
                1
            }
            state.setGlobal("greet")
            
            // Execute script
            state.execute("result = greet('Android Developer')")
            
            // Retrieve global result
            state.getGlobal("result")
            val output = state.getString(-1)
            state.pop(1)
            
            state.close()
            
            tv.text = "Luau Script Execution Result:\n\n$output"
        } catch (e: Exception) {
            tv.text = "Error executing Luau Script:\n\n${e.message}"
        }
    }
}
