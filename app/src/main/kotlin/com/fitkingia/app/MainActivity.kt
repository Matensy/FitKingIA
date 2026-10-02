package com.fitkingia.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.fitkingia.core.model.Goal

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val goals = Goal.entries.map { it.name }.sortedBy { it.length }.joinToString { "• $it" }
        setContentView(TextView(this).apply { text = "FitKingIA\n$goals"; textSize = 18f })
    }
}
