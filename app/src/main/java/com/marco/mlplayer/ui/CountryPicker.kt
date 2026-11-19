package com.marco.mlplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.data.Region

/**
 * Scelta del paese dei consigliati. La prima voce, «Automatico», segue il paese in cui ci si trova
 * (rete mobile, SIM, lingua del telefono); le altre sono un paese fisso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(env: AppEnv, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val detected = remember { Region.detect(ctx) }
    var query by remember { mutableStateOf("") }
    val all = remember { Region.CODES.map { it to Region.name(it) }.sortedBy { it.second } }
    val shown = all.filter { it.second.contains(query.trim(), ignoreCase = true) }
    val chosen = env.settings.region

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
            Text("Paese dei consigliati", style = MaterialTheme.typography.titleLarge)
            Text(
                "Le proposte della Home cambiano da paese a paese.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Cerca un paese") },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Cancella") }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (query.isBlank()) {
                    item {
                        CountryRow("Automatico", "Dove ti trovi ora: ${Region.name(detected)}", chosen.isBlank()) {
                            env.setRegion(""); onDismiss()
                        }
                    }
                }
                items(shown, key = { it.first }) { (code, name) ->
                    CountryRow(name, null, chosen == code) { env.setRegion(code); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
