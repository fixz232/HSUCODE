package com.hsucode.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = xc.ink) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("模型中心", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = xc.ink)
                Text("管理连接、模型能力与调用分配", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = xc.sub)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ModelCenterTab.values().toList()) { tab ->
                FilterChip(
                    selected = selected == tab,
                    onClick = { selected = tab },
                    label = { Text(tab.label) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                )
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
