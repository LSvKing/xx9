package com.xx9.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 首次 / 设置入口：仅 Base URL（AMEND-1：无密码门）。
 * 视觉对齐 UI-CAST §1.2。
 */
class ServerSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        val existing = Prefs.baseUrl(this)
        if (!force && existing.isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }

        setContentView(R.layout.activity_server_setup)
        val input = findViewById<EditText>(R.id.baseUrl)
        val err = findViewById<TextView>(R.id.err)
        val save = findViewById<Button>(R.id.save)
        if (existing.isNotEmpty()) input.setText(existing)

        save.setOnClickListener {
            val norm = Prefs.normalizeOrNull(input.text.toString())
            if (norm == null) {
                err.text = getString(R.string.setup_err_invalid)
                err.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val changed = norm != existing
            Prefs.setBaseUrl(this, norm)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_CLEAR_SESSION, changed),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_FORCE = "force_setup"
    }
}
