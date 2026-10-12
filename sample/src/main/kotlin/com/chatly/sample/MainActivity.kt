package com.chatly.sample

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.chatly.Chatly

/**
 * Minimal demo host. Configures the SDK on first launch and presents the
 * built-in [com.chatly.ui.ChatlyActivity] from a button tap.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Chatly.configure(
            context = applicationContext,
            channelPublicId = "CH_sample123",
            baseUrl = "https://api.chatlychat.com",
        )

        findViewById<Button>(R.id.btn_open_chat).setOnClickListener {
            Chatly.shared().present(this)
        }
    }
}
