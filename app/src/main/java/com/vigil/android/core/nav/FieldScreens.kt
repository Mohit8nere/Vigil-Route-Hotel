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
internal fun TrafficHome(onChallan: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Traffic Police", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onChallan) { Text("Challan") }
            TextButton(onClick = onLogout) { Text("Log out") }
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
