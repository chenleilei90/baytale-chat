package com.baytale.baychat.ui.sheets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.PickupTransferProgress
import com.baytale.baychat.core.model.SavedPickupRecord
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayFileTypeIcon
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BayProgressBar
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 离线取件码中心弹窗：支持寄出文件生成取件码、凭码查询并下载文件、以及管理已发出记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupHubSheet(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 使用 rememberSaveable 持久化分段页签与表单输入，旋转屏幕不丢失
    var selectedSegment by rememberSaveable { mutableIntStateOf(0) }

    // 寄出文件表单状态
    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedUri = remember(selectedUriString) { selectedUriString?.let { Uri.parse(it) } }
    var expireHours by rememberSaveable { mutableIntStateOf(24) }
    var burnAfterRead by rememberSaveable { mutableStateOf(false) }
    var sendPassword by rememberSaveable { mutableStateOf("") }
    var awaitingCreatedResult by remember { mutableStateOf(false) }
    var submitStartedAt by remember { mutableLongStateOf(0L) }
    var createdRecord by remember { mutableStateOf<SavedPickupRecord?>(null) }

    // 凭码取件表单状态
    var inputCode by rememberSaveable { mutableStateOf("") }
    var lookupPassword by rememberSaveable { mutableStateOf("") }

    // 撤销取件码二次确认
    var pendingRevokeCode by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedUriString = uri.toString()
            createdRecord = null
        }
    }

    // 监听寄出上传完成并捕获新生成的取件码记录
    LaunchedEffect(state.pickupBusy, state.pickupProgress, state.myPickups) {
        if (awaitingCreatedResult && !state.pickupBusy && state.pickupProgress == null) {
            val latest = state.myPickups.firstOrNull()
            if (latest != null && latest.createdAt >= submitStartedAt - 1_500L) {
                createdRecord = latest
            }
            awaitingCreatedResult = false
        }
    }

    // 离开或销毁弹窗时清理查询态，避免残留上次取件预览
    DisposableEffect(Unit) {
        onDispose {
            vm.clearPickupLookup()
        }
    }

    fun buildPickupShareUrl(code: String): String {
        val base = state.prefs.serverUrl.trim().trimEnd('/')
        return "$base/p/${code.trim()}"
    }

    fun copyText(text: String, tip: String) {
        clipboard.setText(AnnotatedString(text))
        vm.showToast(tip)
    }

    fun triggerLookup() {
        focusManager.clearFocus()
        keyboardController?.hide()
        if (!state.pickupBusy && inputCode.isNotBlank()) {
            vm.lookupPickup(inputCode, lookupPassword)
        }
    }

    val handleDismiss = {
        focusManager.clearFocus()
        keyboardController?.hide()
        vm.clearPickupLookup()
        onDismiss()
    }

    BayBottomSheet(
        onDismiss = handleDismiss,
        title = "离线取件码",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            BaySegmented(
                options = listOf("寄出文件", "凭码取件", "我的记录"),
                selected = selectedSegment,
                onSelect = { next ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    selectedSegment = next
                    vm.clearPickupLookup()
                },
            )

            when (selectedSegment) {
                0 -> {
                    val fileMeta = remember(selectedUri) {
                        selectedUri?.let { MediaDownloadHelper.queryUriMeta(context, it) }
                    }

                    // 已生成取件码高亮展示卡
                    createdRecord?.let { rec ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BayShapes.mdShape)
                                .background(colors.accentSoft)
                                .padding(BaySpacing.lg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            Text(
                                text = "取件码已生成",
                                style = BayType.footnote,
                                color = colors.accent,
                            )
                            Text(
                                text = rec.code,
                                style = BayType.codeLarge,
                                color = colors.textPrimary,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PickupMiddleEllipsisFileName(
                                    fileName = rec.fileName,
                                    style = BayType.footnote,
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Text(
                                    text = " · ${FormatUtils.formatBytes(rec.fileSize)}",
                                    style = BayType.footnote,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                            ) {
                                BayButton(
                                    text = "复制取件码",
                                    onClick = { copyText(rec.code, "已复制取件码 ${rec.code}") },
                                    style = BayButtonStyle.Primary,
                                    size = BayButtonSize.Medium,
                                    leadingIcon = Icons.Rounded.ContentCopy,
                                    modifier = Modifier.weight(1f),
                                )
                                BayButton(
                                    text = "复制取件链接",
                                    onClick = { copyText(buildPickupShareUrl(rec.code), "已复制取件链接") },
                                    style = BayButtonStyle.Secondary,
                                    size = BayButtonSize.Medium,
                                    leadingIcon = Icons.Rounded.Link,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // 待上传文件选择卡
                    if (fileMeta == null) {
                        BayCard(
                            onClick = if (!state.pickupBusy) {
                                { filePicker.launch(arrayOf("*/*")) }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                            ) {
                                BayIconButton(
                                    icon = Icons.Rounded.UploadFile,
                                    contentDescription = "选择文件",
                                    onClick = {
                                        if (!state.pickupBusy) {
                                            filePicker.launch(arrayOf("*/*"))
                                        }
                                    },
                                    style = BayIconStyle.Accent,
                                    size = 44.dp,
                                    enabled = !state.pickupBusy,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "选择要寄出的文件",
                                        style = BayType.headline,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = "支持断点续传上传至中转站，生成 6 位取件码",
                                        style = BayType.footnote,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                        }
                    } else {
                        BayCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                            ) {
                                BayFileTypeIcon(
                                    fileName = fileMeta.fileName,
                                    mimeType = fileMeta.mimeType,
                                    size = 44.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    PickupMiddleEllipsisFileName(
                                        fileName = fileMeta.fileName,
                                        style = BayType.headline,
                                        color = colors.textPrimary,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = FormatUtils.formatBytes(fileMeta.fileSize),
                                        style = BayType.footnote,
                                        color = colors.textSecondary,
                                    )
                                }
                                BayButton(
                                    text = "更换",
                                    onClick = {
                                        if (!state.pickupBusy) {
                                            filePicker.launch(arrayOf("*/*"))
                                        }
                                    },
                                    style = BayButtonStyle.Neutral,
                                    size = BayButtonSize.Small,
                                    enabled = !state.pickupBusy,
                                )
                            }
                        }
                    }

                    // 有效期选择
                    Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                        Text(
                            text = "有效期限",
                            style = BayType.headline,
                            color = colors.textPrimary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            listOf(1 to "1 小时", 24 to "24 小时", 72 to "72 小时").forEach { (hours, label) ->
                                BayChip(
                                    label = label,
                                    selected = expireHours == hours,
                                    onClick = {
                                        if (!state.pickupBusy) {
                                            expireHours = hours
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // 阅后即焚与取件密码
                    BayGroup {
                        BayListRow(
                            icon = Icons.Rounded.LocalFireDepartment,
                            iconTint = colors.warning,
                            title = "阅后即焚",
                            subtitle = "对方完成下载后自动失效销毁",
                            trailing = RowTrailing.Switch(
                                checked = burnAfterRead,
                                onChange = {
                                    if (!state.pickupBusy) {
                                        burnAfterRead = it
                                    }
                                },
                            ),
                        )
                    }

                    BayTextField(
                        value = sendPassword,
                        onValueChange = { if (!state.pickupBusy) sendPassword = it },
                        placeholder = "可选取件密码（留空则无需密码）",
                        leadingIcon = Icons.Rounded.Lock,
                        isPassword = true,
                        enabled = !state.pickupBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        ),
                    )

                    // 上传实时进度条
                    state.pickupProgress?.takeIf { it.isUpload }?.let { progress ->
                        PickupProgressCard(progress = progress)
                    }

                    BayButton(
                        text = if (state.pickupBusy) "正在上传文件..." else "生成取件码",
                        onClick = {
                            if (state.pickupBusy) return@BayButton
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            val targetUri = selectedUri ?: return@BayButton
                            createdRecord = null
                            submitStartedAt = System.currentTimeMillis()
                            awaitingCreatedResult = true
                            vm.createPickup(
                                uri = targetUri,
                                expireHours = expireHours,
                                burnAfterRead = burnAfterRead,
                                password = sendPassword.trim(),
                            )
                        },
                        style = BayButtonStyle.Primary,
                        size = BayButtonSize.Large,
                        leadingIcon = Icons.Rounded.AttachFile,
                        loading = state.pickupBusy,
                        enabled = selectedUri != null && !state.pickupBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                1 -> {
                    // 凭码取件
                    BayTextField(
                        value = inputCode,
                        onValueChange = { next ->
                            if (!state.pickupBusy) {
                                inputCode = next.filter { it.isLetterOrDigit() }.take(12)
                            }
                        },
                        placeholder = "输入 6 位取件码",
                        leadingIcon = Icons.Rounded.QrCode2,
                        large = true,
                        enabled = !state.pickupBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { triggerLookup() },
                        ),
                    )

                    BayTextField(
                        value = lookupPassword,
                        onValueChange = {
                            if (!state.pickupBusy) {
                                lookupPassword = it
                            }
                        },
                        placeholder = "取件密码（无密码请留空）",
                        leadingIcon = Icons.Rounded.Lock,
                        isPassword = true,
                        enabled = !state.pickupBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            },
                        ),
                    )

                    BayButton(
                        text = "查询取件文件",
                        onClick = { triggerLookup() },
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Medium,
                        leadingIcon = Icons.Rounded.Search,
                        loading = state.pickupBusy && state.pickupProgress == null,
                        enabled = inputCode.isNotBlank() && !state.pickupBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val lookupInfo = state.pickupLookupResult
                    if (lookupInfo != null) {
                        BayCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                            ) {
                                BayFileTypeIcon(
                                    fileName = lookupInfo.fileName.ifBlank { "file" },
                                    mimeType = lookupInfo.mimeType,
                                    size = 46.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                                ) {
                                    PickupMiddleEllipsisFileName(
                                        fileName = lookupInfo.fileName.ifBlank { "离线文件" },
                                        style = BayType.headline,
                                        color = colors.textPrimary,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    val metaParts = buildList {
                                        add(FormatUtils.formatBytes(lookupInfo.fileSize))
                                        if (lookupInfo.files.size > 1) add("共 ${lookupInfo.files.size} 个文件")
                                        if (lookupInfo.expiresAt > 0L) add(FormatUtils.formatExpiry(lookupInfo.expiresAt))
                                        if (lookupInfo.burnAfterRead) add("阅后即焚")
                                    }
                                    Text(
                                        text = metaParts.joinToString(" · "),
                                        style = BayType.footnote,
                                        color = colors.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(BaySpacing.md))

                            state.pickupProgress?.takeIf { !it.isUpload }?.let { progress ->
                                PickupProgressCard(progress = progress)
                                Spacer(modifier = Modifier.height(BaySpacing.md))
                            }

                            BayButton(
                                text = if (state.pickupBusy) "正在下载..." else "下载到下载目录",
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    if (!state.pickupBusy) {
                                        vm.downloadPickup(lookupInfo.code, lookupPassword)
                                    }
                                },
                                style = BayButtonStyle.Primary,
                                size = BayButtonSize.Large,
                                leadingIcon = Icons.Rounded.Download,
                                loading = state.pickupBusy,
                                enabled = !state.pickupBusy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        state.pickupProgress?.takeIf { !it.isUpload }?.let { progress ->
                            PickupProgressCard(progress = progress)
                        }
                    }
                }

                2 -> {
                    // 我的取件码记录
                    if (state.myPickups.isEmpty()) {
                        BayEmptyState(
                            icon = Icons.Rounded.Inventory2,
                            title = "暂无已寄出的取件码",
                            message = "在「寄出文件」中选择文件并上传后，取件码记录将自动保存在此处。",
                            actionLabel = "去寄出文件",
                            onAction = { selectedSegment = 0 },
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                            state.myPickups.forEach { rec ->
                                PickupRecordCard(
                                    record = rec,
                                    onCopyCode = { copyText(rec.code, "已复制取件码 ${rec.code}") },
                                    onCopyLink = { copyText(buildPickupShareUrl(rec.code), "已复制取件链接") },
                                    onRevoke = { pendingRevokeCode = rec.code },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRevokeCode?.let { targetCode ->
        BayDialog(
            title = "撤销取件码 $targetCode？",
            message = "撤销后该取件码将立即失效，接收方将无法继续通过此码下载文件。",
            confirmText = "确认撤销",
            onConfirm = {
                vm.revokePickup(targetCode)
                if (createdRecord?.code.equals(targetCode, ignoreCase = true)) {
                    createdRecord = null
                }
                pendingRevokeCode = null
            },
            dismissText = "取消",
            onDismiss = { pendingRevokeCode = null },
            destructive = true,
        )
    }
}

/**
 * 保留文件扩展名的中间省略文件名展示组件，确保 .apk / .zip / .pptx 等后缀始终可见。
 */
@Composable
private fun PickupMiddleEllipsisFileName(
    fileName: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val (stem, ext) = remember(fileName) { FormatUtils.splitFileNameAndExt(fileName) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stem,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ext.isNotEmpty()) {
            Text(
                text = ext,
                style = style,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PickupProgressCard(progress: PickupTransferProgress) {
    val colors = BayTheme.colors
    val fraction = if (progress.totalBytes > 0L) {
        (progress.transferredBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BayShapes.smShape)
            .background(colors.fill)
            .padding(BaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        if (!progress.errorMessage.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = progress.errorMessage,
                    style = BayType.footnote,
                    color = colors.danger,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickupMiddleEllipsisFileName(
                    fileName = progress.fileName.ifBlank { if (progress.isUpload) "正在上传" else "正在下载" },
                    style = BayType.footnote,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                val speedText = if (progress.speedBps > 0.0) {
                    " · ${FormatUtils.formatBytes(progress.speedBps.toLong())}/s"
                } else {
                    ""
                }
                Text(
                    text = "$percent%$speedText",
                    style = BayType.caption,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
            BayProgressBar(fraction = fraction)
            Text(
                text = "${FormatUtils.formatBytes(progress.transferredBytes)} / ${FormatUtils.formatBytes(progress.totalBytes)}",
                style = BayType.caption,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun PickupRecordCard(
    record: SavedPickupRecord,
    onCopyCode: () -> Unit,
    onCopyLink: () -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = BayTheme.colors
    val expired = record.expiresAt > 0L && record.expiresAt <= System.currentTimeMillis()
    val expiryText = when {
        expired -> "已过期"
        record.expiresAt > 0L -> FormatUtils.formatExpiry(record.expiresAt)
        else -> "有效"
    }

    BayCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayFileTypeIcon(
                fileName = record.fileName,
                mimeType = record.mimeType,
                size = 42.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Text(
                        text = record.code,
                        style = BayType.headline.copy(fontWeight = FontWeight.Bold),
                        color = if (expired) colors.textTertiary else colors.accent,
                    )
                    if (record.burnAfterRead) {
                        Text(
                            text = "阅后即焚",
                            style = BayType.caption,
                            color = colors.warning,
                            modifier = Modifier
                                .clip(BayShapes.fullShape)
                                .background(colors.warning.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                                .padding(horizontal = BaySpacing.sm, vertical = 2.dp),
                        )
                    }
                }
                PickupMiddleEllipsisFileName(
                    fileName = record.fileName,
                    style = BayType.subhead,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${FormatUtils.formatBytes(record.fileSize)} · $expiryText",
                    style = BayType.caption,
                    color = if (expired) colors.danger else colors.textSecondary,
                )
            }
            BayIconButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = "复制取件码",
                onClick = onCopyCode,
                style = BayIconStyle.Filled,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.Link,
                contentDescription = "复制取件链接",
                onClick = onCopyLink,
                style = BayIconStyle.Accent,
                size = 34.dp,
            )
            BayIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "撤销取件码",
                onClick = onRevoke,
                style = BayIconStyle.Plain,
                size = 34.dp,
            )
        }
    }
}
