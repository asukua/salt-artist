package androidx.compose.ui

import androidx.compose.runtime.Composer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object NavigationTintTestHost {
    var tint by mutableStateOf(Color(0x64FFFFFF))
    var preserve by mutableStateOf(false)
}

class `ں` {
    companion object {
        @JvmStatic fun `Ϳ`(composer: Composer): Long = NavigationTintTestHost.tint.value.toLong()
    }
}
