package com.marco.mlplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.marco.mlplayer.yt.Yt

/** Sceglie a quale delle tue playlist aggiungere il brano, o ne crea una nuova. */
@Composable
internal fun AddToPlaylistDialog(env: AppEnv, item: Yt.Item, onDismiss: () -> Unit) {
    val st = env.app.ytStore
    var creating by remember { mutableStateOf(st.myPlaylists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi a una playlist") },
        text = {
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome della nuova playlist") })
            } else {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Nuova playlist…", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    )
                    for (p in st.myPlaylists) Row(
                        Modifier.fillMaxWidth().clickable {
                            env.message = if (st.addToPlaylist(p.id, item)) "Aggiunto a «${p.name}»" else "Era già in «${p.name}»"
                            onDismiss()
                        }.padding(vertical = 12.dp),
                    ) {
                        Text("${p.name} · ${p.items.size}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(onClick = {
                val p = st.createPlaylist(name)
                st.addToPlaylist(p.id, item)
                env.message = "Aggiunto a «${p.name}»"
                onDismiss()
            }) { Text("Crea e aggiungi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
