package com.lostf1sh.pixelplayeross.presentation.telegram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.theme.PixelPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import org.drinkless.tdlib.TdApi
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@AndroidEntryPoint
class TelegramActivity : ComponentActivity() {
    private val telegramViewModel: TelegramViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixelPlayerTheme {
                TelegramMusicScreen(
                    viewModel = telegramViewModel,
                    onBack = ::finish,
                    onPlay = { songs, selected, queueName ->
                        // Keep this Activity alive while its ViewModel completes the queue
                        // handoff. The playback service continues after the user presses Back.
                        playerViewModel.playSongs(songs, selected, queueName)
                    },
                )
            }
        }
    }
}


@Composable
private fun TelegramLoginQrCode(link: String) {
    val bitmap = androidx.compose.runtime.remember(link) {
        runCatching {
            val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until matrix.width) {
                    for (y in 0 until matrix.height) {
                        setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
                    }
                }
            }
        }.getOrNull()
    }

    if (bitmap == null) {
        Text("Could not render the Telegram sign-in QR code. Use phone sign-in instead.")
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Telegram sign-in QR code",
            modifier = Modifier.size(260.dp).padding(12.dp),
        )
    }
}

@Composable
private fun TelegramMusicScreen(
    viewModel: TelegramViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onPlay: (List<Song>, Song, String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedChannel = state.selectedChannel
    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Telegram Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Stream audio from channels you can access", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.authorizationState is TdApi.AuthorizationStateReady) {
                        IconButton(onClick = viewModel::refreshJoinedChannels, enabled = !state.isLoading) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh channels")
                        }
                        IconButton(onClick = viewModel::logout, enabled = !state.isLoading) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
                        }
                    }
                }
            }

            state.initializationError?.let { message ->
                item { MessageCard(message, isError = true) }
            }

            if (state.hasApiCredentials) {
                item {
                    TextButton(
                        onClick = viewModel::resetApiCredentials,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Change Telegram API credentials · signs out")
                    }
                }
            }

            if (state.authorizationState !is TdApi.AuthorizationStateReady) {
                if (
                    !state.hasApiCredentials &&
                    state.initializationError?.contains("API credentials") == true
                ) {
                    item {
                        SectionCard(
                            title = "Set up Telegram access",
                            subtitle = "Create your Telegram API credentials at my.telegram.org. They are stored encrypted on this device and are never sent to the AI provider.",
                        ) {
                            OutlinedTextField(
                                value = state.apiIdInput,
                                onValueChange = viewModel::updateApiId,
                                label = { Text("Telegram API ID") },
                                placeholder = { Text("Numeric API ID") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = state.apiHashInput,
                                onValueChange = viewModel::updateApiHash,
                                label = { Text("Telegram API hash") },
                                placeholder = { Text("32-character API hash") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = viewModel::submitApiCredentials,
                                enabled = !state.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Save securely and continue")
                            }
                        }
                    }
                } else {
                when (state.authorizationState) {
                    is TdApi.AuthorizationStateWaitPhoneNumber -> {
                        item {
                            SectionCard(title = "Connect your account", subtitle = "Your account session is stored locally by TDLib on this device.") {
                                OutlinedTextField(
                                    value = state.phoneNumber,
                                    onValueChange = viewModel::updatePhoneNumber,
                                    label = { Text("Phone number") },
                                    placeholder = { Text("+91…") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(onClick = viewModel::submitPhoneNumber, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                                    Text("Continue with phone number")
                                }
                                OutlinedButton(
                                    onClick = viewModel::requestQrCodeLogin,
                                    enabled = !state.isLoading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Sign in with QR code")
                                }
                            }
                        }
                    }
                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                        item {
                            SectionCard(
                                title = "Scan with Telegram",
                                subtitle = "On a device already signed in to Telegram, open Settings → Devices → Link Desktop Device and scan this QR code. The code refreshes automatically.",
                            ) {
                                TelegramLoginQrCode(state.authorizationState.link)
                            }
                        }
                    }
                    is TdApi.AuthorizationStateWaitCode -> {
                        item {
                            SectionCard(title = "Verify your number", subtitle = "Enter the code Telegram sent to your account.") {
                                OutlinedTextField(
                                    value = state.code,
                                    onValueChange = viewModel::updateCode,
                                    label = { Text("Verification code") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(onClick = viewModel::submitCode, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                                    Text("Verify code")
                                }
                            }
                        }
                    }
                    is TdApi.AuthorizationStateWaitPassword -> {
                        item {
                            SectionCard(title = "Two-step verification", subtitle = "Your Telegram account requires its additional password.") {
                                OutlinedTextField(
                                    value = state.password,
                                    onValueChange = viewModel::updatePassword,
                                    label = { Text("Telegram password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(onClick = viewModel::submitPassword, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                                    Text("Continue")
                                }
                            }
                        }
                    }
                    else -> {
                        item {
                            SectionCard(
                                title = "Preparing Telegram",
                                subtitle = state.initializationError
                                    ?: "TDLib is opening your saved session. On first use, enter your phone number when prompted.",
                            ) {
                                if (state.initializationError == null) CircularProgressIndicator()
                            }
                        }
                    }
                }
                }
            } else {
                item {
                    SectionCard(
                        title = "Add a channel",
                        subtitle = "Use @username, a public t.me link, or a private t.me/+ invite link. Private channels must allow your account to join.",
                    ) {
                        OutlinedTextField(
                            value = state.channelInput,
                            onValueChange = viewModel::updateChannelInput,
                            label = { Text("Channel or invite link") },
                            placeholder = { Text("@channel or https://t.me/+…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = viewModel::addChannel, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.CloudQueue, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Add and scan audio")
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Your Telegram chats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::refreshJoinedChannels, enabled = !state.isLoading) { Text("Refresh") }
                    }
                }
                items(state.channels, key = { it.chatId }) { channel ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.isLoading) { viewModel.selectChannel(channel) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.selectedChannel?.chatId == channel.chatId)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(channel.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(if (channel.isChannel) "Channel" else "Group", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.selectedChannel?.chatId == channel.chatId && state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            if (channel.isSaved) {
                                IconButton(
                                    onClick = { viewModel.removeSavedChannel(channel) },
                                    enabled = !state.isLoading,
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remove saved channel",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (selectedChannel != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(selectedChannel.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${state.songs.size} audio tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = viewModel::refreshSelectedChannel, enabled = !state.isLoading) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.size(5.dp))
                                Text("Sync")
                            }
                            if (state.songs.isNotEmpty()) {
                                IconButton(onClick = {
                                    onPlay(state.songs, state.songs.first(), "Telegram · ${selectedChannel.title}")
                                }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play all")
                                }
                            }
                        }
                    }
                    items(state.songs, key = { it.id }) { song ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onPlay(state.songs, song, "Telegram · ${selectedChannel.title}")
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
                            }
                        }
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.status ?: "Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (!state.status.isNullOrBlank()) {
                item { Text(state.status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.error?.let { message ->
                item { MessageCard(message, isError = true) }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            },
        )
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
