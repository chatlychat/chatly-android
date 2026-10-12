package com.chatly.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.chatly.R

/**
 * Default host activity. Customers who want their own chrome should embed
 * [ChatlyFragment] directly in their own activity instead.
 */
public class ChatlyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.chatly_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.chatly_container, ChatlyFragment.newInstance())
                .commit()
        }
    }
}
