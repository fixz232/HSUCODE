package com.hsucode.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hsucode.data.AppDatabase
import com.hsucode.security.KeystoreProvider

/** Keeps function assignments and auxiliary delegation in one discoverable tab. */
@Composable
fun FunctionAssignmentCenterScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit,
    showHeader: Boolean = true
) {
    var section by rememberSaveable { mutableStateOf("功能分配") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == "功能分配", onClick = { section = "功能分配" }, label = { Text("功能分配") })
            FilterChip(selected = section == "模型委托", onClick = { section = "模型委托" }, label = { Text("模型委托") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (section == "功能分配") {
                FunctionModelsScreen(database, keystore, onBack, showHeader = showHeader)
            } else {
                AuxModelsScreen(database, onBack, showHeader = showHeader)
            }
        }
    }
}
