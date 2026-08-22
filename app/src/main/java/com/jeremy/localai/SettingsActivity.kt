package com.jeremy.localai

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val prefs = getSharedPreferences("ai_settings", MODE_PRIVATE)
        val threadsInput = findViewById<EditText>(R.id.threadsEditText)
        val tempInput = findViewById<EditText>(R.id.tempEditText)

        threadsInput.setText(prefs.getInt("threads", 4).toString())
        tempInput.setText(prefs.getFloat("temperature", 0.7f).toString())

        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            val threads = threadsInput.text.toString().toIntOrNull() ?: 4
            val temp = tempInput.text.toString().toFloatOrNull() ?: 0.7f

            prefs.edit().putInt("threads", threads).putFloat("temperature", temp).apply()
            finish()
        }
    }
}
