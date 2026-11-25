package com.baytale.baychat.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 底部聊天输入区：支持文字输入、语音录制与波形状态、引用回复、相册/拍照/文件/文件夹/剪贴板附件面板。
 */
@Composable
fun Composer(
    state: BayChatUiState,
    replyingTo: ReplyQuote?,
    vm: BayChatViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current

    var inputText by rememberSaveable { mutableStateOf("") }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var showAttachmentPanel by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    // 1. 多文件选择器（SAF 零权限）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            showAttachmentPanel = false
            vm.sendUris(uris)
        }
    }

    // 2. 相册图片与视频选择器
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris ->
        if (uris.isNotEmpty()) {
            showAttachmentPanel = false
            vm.sendUris(uris)
        }
    }

    // 3. 系统相机拍照直发
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            showAttachmentPanel = false
            vm.sendUris(listOf(uri))
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = vm.createCameraUri(isVideo = false)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            vm.showToast("请先授予相机权限后再拍照")
        }
    }

    // 4. 文件夹打包 ZIP 发送
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            showAttachmentPanel = false
            vm.sendFolder(treeUri)
        }
    }

    // 5. 麦克风录音权限
    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startVoiceRecording()
        } else {
            vm.showToast("请先授予麦克风权限后再录制语音")
        }
    }

    fun launchCameraCapture() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val uri = vm.createCameraUri(isVideo = false)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun triggerVoiceRecordStart() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.startVoiceRecording()
        } else {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding(),
    ) {
        // 顶部分隔发丝线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.separator),
        )

        // 引用回复预览条
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            replyingTo?.let { quote ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = BaySpacing.md, end = BaySpacing.md, top = BaySpacing.sm)
                        .clip(BayShapes.smShape)
                        .background(colors.fill)
                        .padding(horizontal = BaySpacing.md, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(22.dp)
                            .clip(BayShapes.fullShape)
                            .background(colors.accent),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Reply,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = "回复 ${quote.senderName}：${quote.preview}",
                        style = BayType.footnote,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BayIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = "取消引用",
                        onClick = { vm.setReplyingTo(null) },
                        style = BayIconStyle.Plain,
                        size = 24.dp,
                    )
                }
            }
        }

        // 主输入栏或语音录制面板
        if (state.isRecordingVoice) {
            RecordingStatusBar(
                durationMs = state.voiceRecordDurationMs,
                onCancel = { vm.cancelVoiceRecording() },
                onSend = { vm.stopVoiceRecording(send = true) },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                // 语音 / 键盘模式切换按钮
                BayIconButton(
                    icon = if (voiceMode) Icons.Rounded.Keyboard else Icons.Rounded.Mic,
                    contentDescription = if (voiceMode) "切回键盘输入" else "切换到语音输入",
                    onClick = {
                        voiceMode = !voiceMode
                        if (voiceMode) showAttachmentPanel = false
                    },
                    style = if (voiceMode) BayIconStyle.Accent else BayIconStyle.Filled,
                    size = 40.dp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                if (voiceMode) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .bayClickable(onClick = ::triggerVoiceRecordStart)
                            .height(48.dp)
                            .clip(BayShapes.smShape)
                            .background(colors.fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "点击开始录制语音（最长 60 秒）",
                                style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                                color = colors.textPrimary,
                            )
                        }
                    }
                } else {
                    BayTextField(
                        value = inputText,
                        onValueChange = { next ->
                            inputText = next
                            vm.onDraftTypingChanged(next.isNotBlank())
                        },
                        placeholder = "输入消息...",
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                }

                // 发送按钮（文本非空）或加号附件展开按钮
                if (!voiceMode && inputText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .bayClickable {
                                val textToSend = inputText
                                inputText = ""
                                showAttachmentPanel = false
                                vm.sendTextMessage(textToSend)
                            }
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "发送消息",
                            tint = colors.onAccent,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                } else {
                    BayIconButton(
                        icon = if (showAttachmentPanel) Icons.Rounded.Close else Icons.Rounded.Add,
                        contentDescription = if (showAttachmentPanel) "收起附件面板" else "展开更多功能",
                        onClick = {
                            voiceMode = false
                            showAttachmentPanel = !showAttachmentPanel
                        },
                        style = if (showAttachmentPanel) BayIconStyle.Accent else BayIconStyle.Filled,
                        size = 40.dp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        // 多模态附件宫格面板（纯色圆角图标按钮，零描边）
        AnimatedVisibility(
            visible = showAttachmentPanel && !state.isRecordingVoice,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    AttachmentGridItem(
                        icon = Icons.Rounded.PhotoLibrary,
                        label = "相册",
                        tint = colors.fileTypeColors.image,
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentGridItem(
                        icon = Icons.Rounded.PhotoCamera,
                        label = "拍照",
                        tint = colors.fileTypeColors.video,
                        onClick = ::launchCameraCapture,
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentGridItem(
                        icon = Icons.Rounded.InsertDriveFile,
                        label = "文件",
                        tint = colors.fileTypeColors.doc,
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentGridItem(
                        icon = Icons.Rounded.FolderZip,
                        label = "文件夹",
                        tint = colors.fileTypeColors.archive,
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    AttachmentGridItem(
                        icon = Icons.Rounded.ContentPaste,
                        label = "剪贴板",
                        tint = colors.accent,
                        onClick = {
                            val clipText = readTextFromClipboard(context)
                            if (clipText.isNullOrBlank()) {
                                vm.showToast("当前系统剪贴板为空")
                            } else {
                                vm.sendClipboardSync(clipText)
                                showAttachmentPanel = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.weight(3f))
                }
            }
        }
    }
}

@Composable
private fun RecordingStatusBar(
    durationMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = BayTheme.colors
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voicePulseAlpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm)
            .clip(BayShapes.mdShape)
            .background(colors.fill)
            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.danger.copy(alpha = pulseAlpha)),
        )
        Text(
            text = "正在录音 ${formatDurationMs(durationMs)} / 01:00",
            style = BayType.callout.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        BayButton(
            text = "取消",
            onClick = onCancel,
            style = BayButtonStyle.DestructiveSoft,
            size = BayButtonSize.Small,
        )
        BayButton(
            text = "发送",
            onClick = onSend,
            style = BayButtonStyle.Primary,
            size = BayButtonSize.Small,
        )
    }
}

@Composable
private fun AttachmentGridItem(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    Column(
        modifier = modifier
            .bayClickable(onClick = onClick)
            .clip(BayShapes.mdShape)
            .padding(vertical = BaySpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(BayShapes.mdShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(BayShapes.smShape)
                    .background(tint.copy(alpha = if (colors.isDark) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = label,
            style = BayType.footnote,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}
