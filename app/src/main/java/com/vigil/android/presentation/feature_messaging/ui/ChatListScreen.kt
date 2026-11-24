package com.vigil.android.presentation.feature_messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vigil.android.R
import com.vigil.android.presentation.feature_messaging.data.ChatChannel
import com.vigil.android.presentation.feature_messaging.viewmodel.ChatListViewModel
import com.vigil.android.utils.toTitleCase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onBack: () -> Unit,
    onChatClick: (String) -> Unit,
    viewModel: ChatListViewModel = viewModel()
) {
    val channels by viewModel.channels.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val loadError by viewModel.error.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentFilter by viewModel.currentFilter.collectAsState()
    val roster by viewModel.roster.collectAsState()
    val isGroupCreator by viewModel.isGroupCreator.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val prefs = context.getSharedPreferences("chat_notifications", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var showNewChatDialog by remember { mutableStateOf(false) }
    var rosterSearchQuery by remember { mutableStateOf("") }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var groupDescription by remember { mutableStateOf("") }

    val filteredChannels = remember(channels, searchQuery, currentFilter) {
        var list = when (currentFilter) {
            com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.ALL -> channels
            com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.UNREAD -> channels.filter { it.unreadCount > 0 }
            com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.GROUPS -> channels.filter { it.kind == "group" }
        }
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                        it.lastMessage.contains(searchQuery, ignoreCase = true)
            }
        }
        list
    }

    val greenGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF075E54),
            Color(0xFF128C7E),
            Color(0xFF009688)
        )
    )
    val whatsappBackgroundColor = Color(0xFFFFFFFF)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(whatsappBackgroundColor)
                .systemBarsPadding()
        ) {
            // WhatsApp Top Bar for Chats with Green Gradient
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(greenGradient)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IconButton(onClick = onBack, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    painter = painterResource(id = R.drawable.baseline_arrow_back_ios_new_24),
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                            Text(
                                text = "Vigil Chats",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = {
                            viewModel.loadRoster()
                            showNewChatDialog = true
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.menu_classified),
                                contentDescription = "Menu",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search chats...", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        singleLine = true,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(8.dp))

                    // WhatsApp Filter Chips (All, Unread, Groups)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val unreadCountTotal = channels.count { it.unreadCount > 0 }
                        val groupCountTotal = channels.count { it.kind == "group" }

                        FilterChip(
                            selected = currentFilter == com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.ALL,
                            onClick = { viewModel.setFilter(com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.ALL) },
                            label = { Text("All") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = Color(0xFF075E54),
                                containerColor = Color.White.copy(alpha = 0.2f),
                                labelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = currentFilter == com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.UNREAD,
                            onClick = { viewModel.setFilter(com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.UNREAD) },
                            label = { Text("Unread ($unreadCountTotal)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = Color(0xFF075E54),
                                containerColor = Color.White.copy(alpha = 0.2f),
                                labelColor = Color.White
                            )
                        )
                        FilterChip(
                            selected = currentFilter == com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.GROUPS,
                            onClick = { viewModel.setFilter(com.vigil.android.presentation.feature_messaging.viewmodel.ChatFilter.GROUPS) },
                            label = { Text("Groups ($groupCountTotal)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = Color(0xFF075E54),
                                containerColor = Color(0xFF075E54),
                                labelColor = Color.White
                            )
                        )
                    }
                }
            }

            if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::refreshFromApi) { Text("Retry") }
                }
            }
            // Chat Channels List
            if (filteredChannels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No chats found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap the + button below to start a new chat with officers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        ChatChannelItem(
                            channel = channel,
                            onClick = { onChatClick(channel.id) }
                        )
                        HorizontalDivider(
                            color = Color(0xFFF0F0F0),
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 76.dp)
                        )
                    }
                }
            }
        }

        // WhatsApp Style Floating Action Button (FAB) for New Chat
        FloatingActionButton(
            onClick = {
                viewModel.loadRoster()
                showNewChatDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = Color(0xFF25D366),
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }

    // New Chat / Officer Roster Dialog
    if (showNewChatDialog) {
        AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Start New Chat", fontWeight = FontWeight.Bold, color = Color(0xFF075E54)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = rosterSearchQuery,
                        onValueChange = {
                            rosterSearchQuery = it
                            viewModel.loadRoster(it)
                        },
                        placeholder = { Text("Search officers by name...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))

                    if (isGroupCreator) {
                        Button(
                            onClick = {
                                showNewChatDialog = false
                                showCreateGroupDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075E54))
                        ) {
                            Text("👥 Create New Group Chat")
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(
                        "Scoped Officers Roster:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    ) {
                        items(roster, key = { it.id }) { officer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showNewChatDialog = false
                                        viewModel.startDirectChat(officer.id) { convId ->
                                            onChatClick(convId.toString())
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF075E54)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = officer.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        officer.name.toTitleCase(),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${officer.role.toTitleCase()} (@${officer.username})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                            HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create Group Dialog
    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("Create Group Chat", fontWeight = FontWeight.Bold, color = Color(0xFF075E54)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Group Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = groupDescription,
                        onValueChange = { groupDescription = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            showCreateGroupDialog = false
                            val selectedMemberIds = roster.map { it.id }
                            viewModel.createGroupChat(
                                groupName,
                                groupDescription,
                                selectedMemberIds
                            ) { convId ->
                                onChatClick(convId.toString())
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF075E54))
                ) {
                    Text("Create Group")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ChatChannelItem(
    channel: ChatChannel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar with online status
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF075E54)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = channel.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }
            if (channel.isOnline) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366))
                        .align(Alignment.BottomEnd)
                )
            }
        }

        // Channel Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    maxLines = 1
                )
                Text(
                    text = channel.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (channel.unreadCount > 0) Color(0xFF25D366) else Color.Gray,
                    fontWeight = if (channel.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.DarkGray,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (channel.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF25D366)
                    ) {
                        Text(
                            text = channel.unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
