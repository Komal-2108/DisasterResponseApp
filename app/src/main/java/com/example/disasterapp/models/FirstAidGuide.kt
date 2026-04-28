package com.example.disasterapp.models

data class FirstAidGuide(
    val title: String,
    val iconResId: Int, // Simple built-in drawable reference
    val keywords: List<String>,
    val instructions: String
)
