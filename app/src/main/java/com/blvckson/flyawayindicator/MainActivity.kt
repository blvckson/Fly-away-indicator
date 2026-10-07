package com.blvckson.flyawayindicator

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        findViewById<Button>(R.id.start).setOnClickListener {
            status.text = "Monitor ready — waiting for Aviator live-screen detection."
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            status.text = "Monitor stopped."
        }
    }
}