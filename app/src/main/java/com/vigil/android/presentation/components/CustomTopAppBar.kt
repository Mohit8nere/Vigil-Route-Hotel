package com.vigil.android.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomGradientTopAppBar(
    title: String = "Officers List",
    onBackClick: (() -> Unit)? = null,
    onSearchClick: (() -> Unit)? = null,
    onLogoutClick: (() -> Unit)? = null,
    onPrivacyClick: (() -> Unit)? = null
) {
    val emeraldGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF009688),
            Color(0xFF26A69A)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(emeraldGradient)
            .statusBarsPadding()
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                if (onBackClick != null) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
            },
            actions = {
                if (onPrivacyClick != null) {
                    IconButton(onClick = onPrivacyClick) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Privacy Policy",
                            tint = Color.White
                        )
                    }
                }
                if (onSearchClick != null) {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.White
                        )
                    }
                }
                if (onLogoutClick != null) {
                    IconButton(onClick = onLogoutClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Logout",
                            tint = Color.White
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )
    }
}