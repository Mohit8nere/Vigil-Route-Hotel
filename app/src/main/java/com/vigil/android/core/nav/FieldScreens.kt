package com.vigil.android.core.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.vigil.android.presentation.traffic.TrafficDutyScreen
import com.vigil.android.presentation.verification.VerificationScreen
import com.vigil.android.ui.theme.VigilTheme
import dagger.hilt.android.AndroidEntryPoint

@Composable
internal fun TrafficHome(onPatrol: () -> Unit, onVip: () -> Unit, onChat: () -> Unit, onSurveys: () -> Unit, onChallan: (() -> Unit)?, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Traffic duty", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                onChallan?.let { open -> TextButton(onClick = open) { Text("Challan") } }
                IconButton(onClick = onLogout) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_lock_power_off),
                        contentDescription = "Log out",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { AssistChip(onClick = onPatrol, label = { Text("Patrol routes") }) }
            item { AssistChip(onClick = onSurveys, label = { Text("Assigned surveys") }) }
            item { AssistChip(onClick = onVip, label = { Text("VIP duties") }) }
            item { AssistChip(onClick = onChat, label = { Text("Chats") }) }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) { TrafficDutyScreen() }
    }
}

@AndroidEntryPoint
class VerificationTab : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { VigilTheme { VerificationScreen() } }
        }
}
