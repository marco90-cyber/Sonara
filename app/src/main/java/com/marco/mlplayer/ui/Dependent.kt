package com.marco.mlplayer.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.marco.mltheme.LocalPrefEnabled

/**
 * Come MLDependent di MLTheme (voci in grigio che non si possono toccare), ma lascia passare i gesti di scorrimento:
 * quello della libreria blocca anche il trascinamento e la lista non scende né sale sopra le voci spente.
 */
@Composable
fun Dependent(enabled: Boolean, content: @Composable () -> Unit) {
    Box {
        CompositionLocalProvider(LocalPrefEnabled provides enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
        // i tocchi vengono assorbiti (le voci non reagiscono); i trascinamenti passano alla lista
        if (!enabled) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
    }
}
