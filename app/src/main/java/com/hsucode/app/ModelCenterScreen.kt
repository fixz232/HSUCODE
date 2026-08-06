package com.hsucode.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hsucode.data.AppDatabase
import com.hsucode.provider.OpenAiClient
import com.hsucode.security.KeystoreProvider

private enum class ModelCenterTab(val label: String) {
    SUPPLIERS("供应商"), MODELS("模型"), FUNCTIONS("功能分配"), HEALTH("用量与健康")
}

@Composable
fun ModelCenterScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    onBack: () -> Unit
) {
    val xc = LocalHsuColors.current
    var selected by rememberSaveable { mutableStateOf(ModelCenterTab.SUPPLIERS) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink) }
            Text("模型中心", fontSize = 20.sp, color = xc.ink, modifier = Modifier.weight(1f))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ModelCenterTab.values().toList()) { tab ->
                FilterChip(selected = selected == tab, onClick = { selected = tab }, label = { Text(tab.label) }, shape = RoundedCornerShape(8.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selected) {
                ModelCenterTab.SUPPLIERS -> SupplierConfigScreen(database, keystore, openAiClient, onBack, showHeader = false)
                ModelCenterTab.MODELS -> ModelMarketScreen(database, keystore, openAiClient, onBack, showHeader = false)
                ModelCenterTab.FUNCTIONS -> FunctionAssignmentCenterScreen(database, keystore, onBack, showHeader = false)
                ModelCenterTab.HEALTH -> ModelHealthScreen(database, openAiClient, onBack, showHeader = false)
            }
        }
    }
}
