package dev.saltartist.bridge

import androidx.compose.runtime.CompositionLocal
import androidx.compose.ui.text.TextStyle
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/** Read the host's live Salt UI text style; never copy or bundle its font files. */
internal object HostTypography {
    // Resolved only when text is first composed, after the page hooks are installed.
    // SPW 1.19.2: Salt UI ContentStyle.kt, LocalTextStyle getter.
    val textStyleLocal: CompositionLocal<TextStyle>? by lazy {
        runCatching {
            val method = HostReflection.classOrNull("androidx.compose.ui.\u10C7")
                ?.getDeclaredMethod("\u0528") ?: return@runCatching null
            val type = method.genericReturnType as? ParameterizedType ?: return@runCatching null
            if (!Modifier.isStatic(method.modifiers) ||
                !CompositionLocal::class.java.isAssignableFrom(method.returnType) ||
                type.actualTypeArguments.singleOrNull() != TextStyle::class.java
            ) return@runCatching null
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null) as? CompositionLocal<TextStyle>)
        }.getOrNull()
    }
}
