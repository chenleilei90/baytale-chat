package com.baytale.baychat.ui.screens.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.EmojiEmotions
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import java.text.BreakIterator
import kotlin.coroutines.cancellation.CancellationException

/**
 * 底部聊天输入区：支持文字输入、微信式按住说话/上滑取消语音录制、@提及选择、引用回复与多模态附件面板。
 */
@Composable
fun Composer(
    state: BayChatUiState,
    roomCode: String,
    peers: List<PeerProfile>,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    voiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
    showAttachmentPanel: Boolean,
    onShowAttachmentPanelChange: (Boolean) -> Unit,
    showEmojiPanel: Boolean,
    onShowEmojiPanelChange: (Boolean) -> Unit,
    onOpenFavoritesPicker: () -> Unit,
    inputFocusRequester: FocusRequester,
    replyingTo: ReplyQuote?,
    vm: BayChatViewModel,
    onVoiceCancellingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var cameraPermAsked by rememberSaveable { mutableStateOf(false) }
    var audioPermAsked by rememberSaveable { mutableStateOf(false) }
    var permSettingsGuide by rememberSaveable { mutableStateOf<String?>(null) }

    // 解析末尾 @提及 关键词与候选在线成员
    val mentionQuery = remember(inputText, voiceMode) {
        if (voiceMode) null else extractMentionQuery(inputText)
    }
    val mentionCandidates = remember(mentionQuery, peers) {
        val q = mentionQuery ?: return@remember emptyList()
        if (peers.isEmpty()) return@remember emptyList()
        peers.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    }

    // 语音按住/取消态（供按钮视觉与底层指针循环共享）
    var isPressingVoice by remember { mutableStateOf(false) }
    var isCancellingVoice by remember { mutableStateOf(false) }
    val latestIsRecording by rememberUpdatedState(state.isRecordingVoice)
    val latestHapticEnabled by rememberUpdatedState(state.prefs.hapticEnabled)
    val latestOnCancellingChanged by rememberUpdatedState(onVoiceCancellingChanged)
    val cancelThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }

    // 1. 多文件选择器（SAF 零权限）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onShowAttachmentPanelChange(false)
            vm.sendUris(uris)
        }
    }

    // 2. 相册图片与视频选择器
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onShowAttachmentPanelChange(false)
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
            onShowAttachmentPanelChange(false)
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
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            if (cameraPermAsked && !canAskAgain) {
                permSettingsGuide = "camera"
            } else {
                cameraPermAsked = true
                vm.showToast("请先授予相机权限后再拍照")
            }
        }
    }

    // 4. 文件夹打包 ZIP 发送
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            onShowAttachmentPanelChange(false)
            vm.sendFolder(treeUri)
        }
    }

    // 5. 麦克风录音权限（授权后仅提示用户按住说话，不在回调中自动启动录音；永久拒绝则引导系统设置）
    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.showToast("已授予麦克风权限，请按住说话")
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            if (audioPermAsked && !canAskAgain) {
                permSettingsGuide = "audio"
            } else {
                audioPermAsked = true
                vm.showToast("请先授予麦克风权限后再录制语音")
            }
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

    // 注意：外层 ChatRoomScreen 已统一应用 WindowInsets.ime.union(WindowInsets.navigationBars)，此处不重复加 navigationBarsPadding
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        // 顶部分隔发丝线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.separator),
        )

        // @提及 在线成员快速选择浮层（输入 @ 或 @关键词 且存在匹配成员时弹出）
        AnimatedVisibility(
            visible = mentionCandidates.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = BaySpacing.md, end = BaySpacing.md, top = BaySpacing.sm)
                    .shadow(elevation = 4.dp, shape = BayShapes.mdShape, clip = false)
                    .clip(BayShapes.mdShape)
                    .background(colors.surfaceElevated)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = BaySpacing.xs),
            ) {
                mentionCandidates.forEach { peer ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bayClickable {
                                val updated = replaceTrailingMention(inputText, peer.name)
                                onInputTextChange(updated)
                                vm.updateDraft(roomCode, updated)
                                vm.onDraftTypingChanged(updated.isNotBlank())
                            }
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        BayAvatar(emojiId = peer.avatar, size = 28.dp)
                        Text(
                            text = peer.name,
                            style = BayType.callout,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 引用回复预览条（取消按钮保证 >= 44.dp 触摸热区）
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
                        .padding(start = BaySpacing.md, end = BaySpacing.xs, top = 2.dp, bottom = 2.dp),
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
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                            .bayClickable { vm.setReplyingTo(null) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "取消引用",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // 主输入栏（语音按住说话按钮保持常驻在位，确保录音中不会卸载而中断指针捕获）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
        ) {
            // 语音 / 键盘模式切换按钮（切至语音时同步隐藏软键盘并收起面板）
            BayIconButton(
                icon = if (voiceMode) Icons.Rounded.Keyboard else Icons.Rounded.Mic,
                contentDescription = if (voiceMode) "切回键盘输入" else "切换到语音输入",
                onClick = {
                    val next = !voiceMode
                    onVoiceModeChange(next)
                    if (next) {
                        onShowAttachmentPanelChange(false)
                        onShowEmojiPanelChange(false)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                },
                style = if (voiceMode) BayIconStyle.Accent else BayIconStyle.Filled,
                size = 40.dp,
                enabled = !state.isRecordingVoice,
                modifier = Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .padding(bottom = 2.dp),
            )

            if (voiceMode) {
                val activeRecordingUi = isPressingVoice || state.isRecordingVoice
                val holdBg = when {
                    activeRecordingUi && isCancellingVoice -> colors.danger.copy(
                        alpha = if (colors.isDark) 0.26f else 0.14f,
                    )
                    activeRecordingUi -> colors.fillStrong
                    else -> colors.fill
                }
                val holdLabel = when {
                    activeRecordingUi && isCancellingVoice -> "松开 取消"
                    activeRecordingUi -> "松开 结束"
                    else -> "按住 说话"
                }
                val holdTextColor = when {
                    activeRecordingUi && isCancellingVoice -> colors.danger
                    else -> colors.textPrimary
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(BayShapes.smShape)
                        .background(holdBg)
                        .pointerInput(cancelThresholdPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@awaitEachGesture
                                }

                                down.consume()
                                BayHaptic.heavy(haptic, latestHapticEnabled)
                                isPressingVoice = true
                                isCancellingVoice = false
                                latestOnCancellingChanged(false)

                                val started = vm.startVoiceRecording()
                                if (!started) {
                                    isPressingVoice = false
                                    isCancellingVoice = false
                                    latestOnCancellingChanged(false)
                                    return@awaitEachGesture
                                }

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null) {
                                            if (latestIsRecording) {
                                                vm.cancelVoiceRecording()
                                            }
                                            break
                                        }

                                        if (!change.pressed) {
                                            val normalRelease = (event.type == PointerEventType.Release ||
                                                change.changedToUpIgnoreConsumed()) && !change.isConsumed
                                            change.consume()
                                            if (!normalRelease || isCancellingVoice) {
                                                if (latestIsRecording) {
                                                    vm.cancelVoiceRecording()
                                                }
                                            } else if (latestIsRecording) {
                                                // 若 60 秒已到期自动发送使 isRecordingVoice 变为 false，此处不再重复发送
                                                vm.stopVoiceRecording(send = true)
                                            }
                                            break
                                        }

                                        val dyUp = down.position.y - change.position.y
                                        val nextCancel = dyUp > cancelThresholdPx
                                        if (nextCancel != isCancellingVoice) {
                                            isCancellingVoice = nextCancel
                                            latestOnCancellingChanged(nextCancel)
                                            // UX-29：进出取消阈值均触发一次轻触感提示
                                            BayHaptic.tap(haptic, latestHapticEnabled)
                                        }
                                        change.consume()
                                    }
                                } catch (e: CancellationException) {
                                    if (latestIsRecording) {
                                        vm.cancelVoiceRecording()
                                    }
                                    throw e
                                } finally {
                                    isPressingVoice = false
                                    isCancellingVoice = false
                                    latestOnCancellingChanged(false)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = holdLabel,
                        style = BayType.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = holdTextColor,
                        maxLines = 1,
                    )
                }
            } else {
                BayTextField(
                    value = inputText,
                    onValueChange = { next ->
                        onInputTextChange(next)
                        vm.updateDraft(roomCode, next)
                        vm.onDraftTypingChanged(next.isNotBlank())
                    },
                    placeholder = "输入消息...",
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                if (showAttachmentPanel) onShowAttachmentPanelChange(false)
                                if (showEmojiPanel) onShowEmojiPanelChange(false)
                            }
                        },
                )
            }

            // 表情按钮（仅键盘文本模式下展示，与软键盘和附件面板严格互斥）
            if (!voiceMode) {
                BayIconButton(
                    icon = if (showEmojiPanel) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions,
                    contentDescription = if (showEmojiPanel) "切回键盘输入" else "打开表情面板",
                    onClick = {
                        val nextEmoji = !showEmojiPanel
                        onShowAttachmentPanelChange(false)
                        if (nextEmoji) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            runCatching { inputFocusRequester.requestFocus() }
                            keyboardController?.show()
                        }
                        onShowEmojiPanelChange(nextEmoji)
                    },
                    style = if (showEmojiPanel) BayIconStyle.Accent else BayIconStyle.Filled,
                    size = 40.dp,
                    enabled = !state.isRecordingVoice,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .padding(bottom = 2.dp),
                )
            }

            // 发送按钮（文本非空）或加号附件展开按钮（键盘与附件面板严格互斥）
            if (!voiceMode && inputText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .padding(bottom = 2.dp)
                        .bayClickable {
                            val textToSend = inputText
                            onInputTextChange("")
                            onShowAttachmentPanelChange(false)
                            onShowEmojiPanelChange(false)
                            vm.sendTextMessage(textToSend)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
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
                }
            } else {
                BayIconButton(
                    icon = if (showAttachmentPanel) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = if (showAttachmentPanel) "收起附件面板" else "展开更多功能",
                    onClick = {
                        val nextPanel = !showAttachmentPanel
                        onVoiceModeChange(false)
                        onShowEmojiPanelChange(false)
                        if (nextPanel) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                        onShowAttachmentPanelChange(nextPanel)
                    },
                    style = if (showAttachmentPanel) BayIconStyle.Accent else BayIconStyle.Filled,
                    size = 40.dp,
                    enabled = !state.isRecordingVoice,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .padding(bottom = 2.dp),
                )
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
                                BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                vm.sendClipboardSync(clipText)
                                onShowAttachmentPanelChange(false)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentGridItem(
                        icon = Icons.Rounded.BookmarkBorder,
                        label = "收藏",
                        tint = colors.warning,
                        onClick = {
                            onShowAttachmentPanelChange(false)
                            onOpenFavoritesPicker()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.weight(2f))
                }
            }
        }

        // 微信风格表情选择面板（最近使用 + 8 列网格 + 右下角退格/发送操作栏，纯码点渲染零字面量 Emoji）
        val catalogEmojis = remember { EMOJI_CODEPOINTS.map(::emojiString) }
        AnimatedVisibility(
            visible = showEmojiPanel && !voiceMode && !state.isRecordingVoice,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(colors.bg),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.recentEmojis.isNotEmpty()) {
                        Text(
                            text = "最近使用",
                            style = BayType.caption,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(
                                start = BaySpacing.lg,
                                top = BaySpacing.sm,
                                bottom = BaySpacing.xs,
                            ),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = BaySpacing.md),
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                        ) {
                            items(state.recentEmojis, key = { "rec_$it" }) { emoji ->
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                        .clip(BayShapes.xsShape)
                                        .bayClickable {
                                            val updated = inputText + emoji
                                            onInputTextChange(updated)
                                            vm.updateDraft(roomCode, updated)
                                            vm.onDraftTypingChanged(updated.isNotBlank())
                                            vm.recordEmojiUsed(emoji)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(text = emoji, style = BayType.title2)
                                }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = BaySpacing.md,
                            end = BaySpacing.md,
                            top = BaySpacing.sm,
                            bottom = 60.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(catalogEmojis, key = { it }) { emoji ->
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                    .clip(BayShapes.xsShape)
                                    .bayClickable {
                                        val updated = inputText + emoji
                                        onInputTextChange(updated)
                                        vm.updateDraft(roomCode, updated)
                                        vm.onDraftTypingChanged(updated.isNotBlank())
                                        vm.recordEmojiUsed(emoji)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, style = BayType.title2)
                            }
                        }
                    }
                }

                // 右下角浮动删除与发送栏
                val hasText = inputText.isNotBlank()
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = BaySpacing.md, bottom = BaySpacing.md)
                        .clip(BayShapes.mdShape)
                        .background(colors.surface)
                        .padding(horizontal = BaySpacing.xs, vertical = BaySpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
                            .clip(BayShapes.smShape)
                            .background(colors.fill)
                            .bayClickable(enabled = inputText.isNotEmpty()) {
                                val next = deleteLastGraphemeCluster(inputText)
                                onInputTextChange(next)
                                vm.updateDraft(roomCode, next)
                                vm.onDraftTypingChanged(next.isNotBlank())
                            }
                            .padding(horizontal = BaySpacing.md),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Backspace,
                            contentDescription = "删除",
                            tint = if (inputText.isNotEmpty()) colors.textPrimary else colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 56.dp, minHeight = 40.dp)
                            .clip(BayShapes.smShape)
                            .background(if (hasText) colors.accent else colors.fill)
                            .bayClickable(enabled = hasText) {
                                val textToSend = inputText
                                onInputTextChange("")
                                vm.sendTextMessage(textToSend)
                            }
                            .padding(horizontal = BaySpacing.md),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "发送",
                            style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                            color = if (hasText) colors.onAccent else colors.textTertiary,
                        )
                    }
                }
            }
        }
    }

    // 权限被永久拒绝时引导前往系统设置页开启
    permSettingsGuide?.let { permType ->
        val isCamera = permType == "camera"
        BayDialog(
            title = if (isCamera) "需要相机权限" else "需要麦克风权限",
            message = if (isCamera) {
                "相机权限已被拒绝，请在系统设置中开启后才能拍照发送图片。"
            } else {
                "麦克风权限已被拒绝，请在系统设置中开启后才能按住录制语音。"
            },
            confirmText = "去系统设置开启",
            onConfirm = {
                permSettingsGuide = null
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
            dismissText = "暂不",
            onDismiss = { permSettingsGuide = null },
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun extractMentionQuery(text: String): String? {
    val atIndex = text.lastIndexOf('@')
    if (atIndex < 0) return null
    if (atIndex > 0 && !text[atIndex - 1].isWhitespace()) return null
    val after = text.substring(atIndex + 1)
    if (after.length > 24 || after.any { it.isWhitespace() }) return null
    return after
}

private fun replaceTrailingMention(text: String, peerName: String): String {
    val atIndex = text.lastIndexOf('@')
    if (atIndex < 0) return text
    return text.substring(0, atIndex) + "@$peerName "
}

private fun deleteLastGraphemeCluster(text: String): String {
    if (text.isEmpty()) return text
    val iter = BreakIterator.getCharacterInstance()
    iter.setText(text)
    val end = iter.last()
    val prev = iter.previous()
    return if (prev != BreakIterator.DONE && prev < end) text.substring(0, prev) else ""
}
