package com.hsucode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.hsucode.app.R
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Full Termux terminal-view surface for the rootless Ubuntu backend.
 * TerminalSession owns a native PTY; TerminalView renders ANSI escape codes,
 * selection, cursor state and IME input instead of treating output as lines.
 */
@Composable
internal fun TermuxPtyTerminal(
    modifier: Modifier = Modifier,
    restartToken: Int = 0,
    sessionId: String = TermuxPtySessionManager.activeId(),
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 13.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    }
    var finished by remember(restartToken, sessionId) { mutableStateOf(false) }
    var reconnectToken by remember(restartToken, sessionId) { mutableStateOf(0) }
    var controlDown by remember(restartToken, sessionId) { mutableStateOf(false) }
    var altDown by remember(restartToken, sessionId) { mutableStateOf(false) }
    val sessionClient = remember(restartToken, sessionId) {
        HsucodeTerminalSessionClient(context.applicationContext) {
            finished = true
        }
    }
    val viewClient = remember(restartToken, sessionId) {
        HsucodeTerminalViewClient(context.applicationContext)
    }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    LaunchedEffect(finished) {
        if (finished) {
            // A user typing `exit`, a killed process, or a transient PTY disconnect
            // should leave the tab usable without requiring a manual page restart.
            delay(500)
            TermuxPtySessionManager.restart(sessionId)
            finished = false
            reconnectToken++
        }
    }

    val sessionState by produceState<TerminalSessionState>(
        initialValue = TerminalSessionState.Loading,
        restartToken,
        reconnectToken,
        sessionId,
        ProotLinuxEnvironment.state,
        sessionClient,
    ) {
        val prepared = withContext(Dispatchers.IO) {
            ProotLinuxEnvironment.preparePtyTerminal()
        }
        if (!prepared) {
            value = TerminalSessionState.Unavailable
            return@produceState
        }
        if (!isActive) return@produceState
        if (restartToken > 0) TermuxPtySessionManager.restart(sessionId)
        val session = TermuxPtySessionManager.obtain(sessionClient, sessionId)
        if (!isActive) {
            session?.let(TermuxPtySessionManager::detach)
            return@produceState
        }
        value = session?.let { TerminalSessionState.Ready(it) }
            ?: TerminalSessionState.Unavailable
    }

    val state = sessionState
    if (state !is TerminalSessionState.Ready) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (state) {
                    TerminalSessionState.Unavailable -> "Ubuntu PTY 尚未就绪"
                    TerminalSessionState.Loading -> "正在启动 Ubuntu PTY…"
                    is TerminalSessionState.Ready -> ""
                },
                color = Color(0xFF9BA4B5),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val session = state.session
    DisposableEffect(session) {
        onDispose {
            sessionClient.terminalView = null
            viewClient.terminalView = null
            TermuxPtySessionManager.detach(session)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp),
            factory = { viewContext ->
                TerminalView(viewContext, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTextSize(terminalTextSizePx)
                    setTypeface(terminalTypeface)
                    setTerminalViewClient(viewClient)
                    attachSession(session)
                    sessionClient.terminalView = this
                    viewClient.terminalView = this
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            viewClient.focusAndShowKeyboard()
                        }
                        false
                    }
                    post { viewClient.focusAndShowKeyboard() }
                }
            },
            update = { terminalView ->
                terminalView.isFocusable = true
                terminalView.isFocusableInTouchMode = true
                terminalView.setTextSize(terminalTextSizePx)
                terminalView.setTypeface(terminalTypeface)
                terminalView.setTerminalViewClient(viewClient)
                sessionClient.terminalView = terminalView
                viewClient.terminalView = terminalView
                terminalView.attachSession(session)
                terminalView.onScreenUpdated()
            },
        )

        if (finished) {
            Text(
                text = "终端会话已退出",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 54.dp),
                color = Color(0xFF9BA4B5),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TermuxExtraKeysBar(
            controlDown = controlDown,
            altDown = altDown,
            onControlToggle = { controlDown = !controlDown },
            onAltToggle = { altDown = !altDown },
            onSendText = { session.writeText(it) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TermuxExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171A21))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("CTRL-C") { onSendText("\u0003") }
        TerminalExtraKey("CTRL-D") { onSendText("\u0004") }
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalIconKey(Icons.Outlined.KeyboardArrowLeft, "左方向键") { onSendText("\u001B[D") }
        TerminalIconKey(Icons.Outlined.KeyboardArrowDown, "下方向键") { onSendText("\u001B[B") }
        TerminalIconKey(Icons.Outlined.KeyboardArrowUp, "上方向键") { onSendText("\u001B[A") }
        TerminalIconKey(Icons.Outlined.KeyboardArrowRight, "右方向键") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .background(
                if (selected) Color(0xFF4D8DFF) else Color(0xFF2A2F3A),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 11.dp, vertical = 8.dp)
            .then(Modifier.clickableWithoutRipple(onClick)),
        color = if (selected) Color.White else Color(0xFFD7DAE0),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun TerminalIconKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(Color(0xFF2A2F3A), RoundedCornerShape(6.dp)),
    ) {
        Icon(icon, contentDescription = description, tint = Color(0xFFD7DAE0))
    }
}

@Composable
private fun Modifier.clickableWithoutRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        onClick = onClick,
    )

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    write(bytes, 0, bytes.size)
}

private sealed interface TerminalSessionState {
    data object Loading : TerminalSessionState
    data object Unavailable : TerminalSessionState
    data class Ready(val session: TerminalSession) : TerminalSessionState
}

private class HsucodeTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal error", e) }
}

private class HsucodeTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        focusAndShowKeyboard()
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = controlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal view error", e) }
}
