package com.peretz.barkannav

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Green = Color(0xFF2E7D32)

@Composable
fun NavScreen(store: Store) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Color(0xFF1565C0))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF4F6F4)) {
            Column(
                Modifier
                    .systemBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Barkan Nav", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Green)
                Text("נוסעים ברכב לחניון חנה וסע, וממשיכים בתחבורה ציבורית.", color = Color.DarkGray)
                DestinationCard(store)
                LotCard(store)
                GoCard(store)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DestinationCard(store: Store) {
    val focus = LocalFocusManager.current
    Section("1. לאן נוסעים?") {
        OutlinedTextField(
            value = store.destination.value,
            onValueChange = { store.setDestination(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("יעד (כתובת או שם מקום)") },
            singleLine = true,
            trailingIcon = {
                if (store.destination.value.isNotEmpty()) {
                    IconButton(onClick = { store.setDestination("") }) { Icon(Icons.Default.Clear, "נקה") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        if (store.recents.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                store.recents.forEach { r ->
                    SuggestionChip(onClick = { store.setDestination(r); focus.clearFocus() }, label = { Text(r, maxLines = 1) })
                }
            }
        }
    }
}

@Composable
private fun LotCard(store: Store) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Lot?>(null) }
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }

    fun pickNearest() {
        locating = true
        scope.launch {
            val here = Loc.current(ctx)
            locating = false
            val lot = here?.let { store.nearest(it.latitude, it.longitude) }
            if (lot == null) {
                Toast.makeText(ctx, "לא הצלחתי לקבל מיקום. בחר חניון מהרשימה.", Toast.LENGTH_LONG).show()
            } else {
                store.select(lot)
                Toast.makeText(ctx, "החניון הקרוב: ${lot.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickNearest()
        else Toast.makeText(ctx, "בלי מיקום אפשר לבחור חניון מהרשימה.", Toast.LENGTH_SHORT).show()
    }

    Section("2. באיזה חניון חנה וסע?") {
        Button(
            onClick = {
                if (Loc.hasPermission(ctx)) pickNearest()
                else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.LocationOn, null)
            Spacer(Modifier.padding(4.dp))
            Text(if (locating) "מאתר מיקום..." else "בחר את החניון הקרוב אליי")
        }

        if (store.lots.isEmpty()) {
            Text("עדיין אין חניונים שמורים.", color = Color.DarkGray)
        }
        store.lots.forEach { lot ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { store.select(lot) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = store.selected.value == lot.name, onClick = { store.select(lot) })
                Column(Modifier.weight(1f)) {
                    Text(lot.name, fontWeight = FontWeight.Medium)
                    if (lot.query != lot.name) Text(lot.query, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                }
                IconButton(onClick = { Nav.showLot(ctx, lot) }) { Icon(Icons.Default.Place, "הצג במפה", tint = Green) }
                IconButton(onClick = { removing = lot }) { Icon(Icons.Default.Delete, "מחק", tint = Color.Gray) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null); Text("הוסף חניון")
            }
            TextButton(onClick = { Nav.searchLots(ctx) }) {
                Icon(Icons.Default.Search, null); Text("חפש בגוגל מפות")
            }
        }
        Text(
            "חניון שמצאת בגוגל מפות: לוחצים עליו, \"שיתוף\", ובוחרים Barkan Nav.",
            fontSize = 12.sp, color = Color.Gray,
        )
    }

    if (adding) AddLotDialog(onDismiss = { adding = false }) { store.addLot(it); adding = false }
    removing?.let { lot ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("למחוק את החניון?") },
            text = { Text(lot.name) },
            confirmButton = { TextButton(onClick = { store.removeLot(lot); removing = null }) { Text("מחק") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun AddLotDialog(onDismiss: () -> Unit, onAdd: (Lot) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("חניון חדש") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("שם, למשל: חנה וסע שפירים") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("כתובת (לא חובה)") }, singleLine = true)
                Text("השם והכתובת נשלחים לחיפוש ב-Waze ובגוגל מפות, אז כדאי לכתוב אותם כמו שהם מופיעים במפה.", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val n = name.trim()
                    val q = if (address.isBlank()) n else "$n, ${address.trim()}"
                    onAdd(Lot(n, q))
                },
            ) { Text("שמור") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

@Composable
private fun GoCard(store: Store) {
    val lot = store.selectedLot
    val dest = store.destination.value.trim()

    Section("3. יוצאים לדרך") {
        if (lot == null) {
            Text("בחר חניון כדי להתחיל.", color = Color.DarkGray)
        } else {
            GoSteps(store, lot, dest)
        }
    }
}

@Composable
private fun GoSteps(store: Store, lot: Lot, dest: String) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("שלב א: ברכב עד ${lot.name}", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Nav.driveWaze(ctx, lot) }, modifier = Modifier.weight(1f)) { Text("🚗 Waze") }
            OutlinedButton(onClick = { Nav.driveGoogle(ctx, lot) }, modifier = Modifier.weight(1f)) { Text("🚗 גוגל מפות") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (dest.isEmpty()) "שלב ב: תחבורה ציבורית ליעד (צריך למלא יעד למעלה)" else "שלב ב: תחבורה ציבורית אל $dest",
            fontWeight = FontWeight.Medium,
        )
        Button(
            onClick = {
                store.rememberDestination(dest)
                Nav.transit(ctx, lot, dest)
            },
            enabled = dest.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🚆 מסלול בתחבורה ציבורית") }
        Text(
            "אפשר לפתוח את שלב ב כבר עכשיו כדי לבדוק זמנים, או כשמגיעים לחניון.",
            fontSize = 13.sp, color = Color.Gray,
        )
    }
}
