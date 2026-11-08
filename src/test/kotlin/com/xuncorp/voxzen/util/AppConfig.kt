package com.xuncorp.voxzen.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TestWindowStyle { None, WindowsAcrylic, WindowsMica, WindowsMicaAlt }

object AppConfig {
    var spwWindowStyle by mutableStateOf(TestWindowStyle.None)
}
