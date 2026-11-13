package com.turnypt.app.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.turnypt.app.MainActivity
import com.turnypt.app.R
import com.turnypt.app.companion.EXTRA_FROM_CLIPBOARD
import com.turnypt.app.companion.DecryptActivity
import com.turnypt.app.companion.EncryptActivity
import com.turnypt.app.companion.ImageCipherActivity
import com.turnypt.data.preferences.SettingsRepository
import com.turnypt.data.repository.MessageCipher

/**
 * Floating assistant (v0.3.0, redesigned v0.5.1): a user-enabled overlay
 * bubble with a labeled quick-action panel.
 *
 * SECURITY CONTRACT:
 *  - the service starts ONLY on an explicit user action (the Settings toggle
 *    or the auto-start after the user had enabled it with the permission
 *    granted — see [AssistantReadiness]);
 *  - it holds no key material and performs no crypto: Encrypt / Decrypt
 *    launch the existing companion activities with the clipboard extra, so
 *    every encryption decision still runs through the audited companion
 *    flow (spec 003/004) with its focus-gated clipboard reads;
 *  - "Lock all sessions" is the one background action: it calls
 *    [MessageCipher.lockAll], which WIPES in-memory keys and nothing else —
 *    a destructive-only panic affordance, never an unlock;
 *  - the service never reads the clipboard itself (Android 10+ denies
 *    clipboard access to unfocused windows) — hence the in-messenger hint
 *    at the bottom of the panel: copy in the messenger, then tap here;
 *  - tapping "Turn off" on the panel persists assistantEnabled = false, so
 *    the bubble does not come back until the user re-enables it.
 *
 * v0.5.1 panel (user feedback "floating assistant is too weak, no
 * options"): labeled rows instead of three bare icons —
 *   Encrypt clipboard · Decrypt clipboard · Lock all sessions ·
 *   Open Turnypt · Turn off — plus the messenger hint line.
 *
 * v0.5.2: a header ✕ collapses the panel with NO action (before, the only
 * ways out were performing an action or turning the assistant off).
 *
 * The overlay needs SYSTEM_ALERT_WINDOW ("Display over other apps"), which
 * cannot be requested at runtime — Settings deep-links the user to it.
 */
@AndroidEntryPoint
class FloatingAssistantService : Service() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var messageCipher: MessageCipher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var bubble: ImageView? = null
    private var panel: LinearLayout? = null
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        startForegroundWithType(buildNotification())
        if (Settings.canDrawOverlays(this)) {
            addOverlay()
        } else {
            // Permission revoked while enabled: stop instead of crashing.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        root?.let { overlay ->
            runCatching { windowManager?.removeView(overlay) }
        }
        root = null
        bubble = null
        panel = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ overlay

    private fun addOverlay() {
        val manager = windowManager ?: return
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pad
            y = (220 * density).toInt()
        }

        // The collapsed bubble.
        val bubbleView = ImageView(this).apply {
            setImageResource(R.drawable.ic_assistant_lock)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(242, 46, 58, 120))
            }
            layoutParams = FrameLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
            ).apply { gravity = Gravity.CENTER }
            setPadding(pad, pad, pad, pad)
        }

        // The expanded quick-action panel (v0.5.1: labeled rows + hint).
        val panelView = buildPanel(density)

        val overlay = FrameLayout(this).apply {
            addView(bubbleView)
            addView(panelView)
        }
        attachDragAndTap(bubbleView, params)

        runCatching { manager.addView(overlay, params) }
            .onFailure { stopSelf(); return }

        root = overlay
        bubble = bubbleView
        panel = panelView
    }

    /** Vertical action panel — header with ✕, labeled rows, hint footer. */
    private fun buildPanel(density: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 22 * density
                setColor(Color.argb(244, 23, 26, 36))
            }
            val hPad = (6 * density).toInt()
            val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            visibility = View.GONE

            // v0.5.2 header: a ✕ that just COLLAPSES the panel back to the
            // bubble — user report: "you can't close or minimize it without
            // doing something; it has no close button". Every row performs an
            // action and 'Turn off' killed the service entirely; this is the
            // no-op way out.
            addView(panelHeader(density))

            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_lock,
                    labelRes = R.string.assistant_encrypt_clipboard,
                    density = density,
                ) { launchClipboardFlow(encrypt = true) },
            )
            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_lock_open,
                    labelRes = R.string.assistant_decrypt_clipboard,
                    density = density,
                ) { launchClipboardFlow(encrypt = false) },
            )
            // v0.6.0: images take their place in the assistant too — same
            // engine as the in-app tabs (the sheet picks the image itself).
            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_image,
                    labelRes = R.string.assistant_encrypt_image,
                    density = density,
                ) { launchImageFlow() },
            )
            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_lock_all,
                    labelRes = R.string.assistant_lock_all,
                    density = density,
                ) { lockAllSessions() },
            )
            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_app,
                    labelRes = R.string.assistant_open_app,
                    density = density,
                ) { openApp() },
            )
            addView(
                panelRow(
                    iconRes = R.drawable.ic_assistant_close,
                    labelRes = R.string.assistant_turn_off,
                    density = density,
                ) { closeAssistant() },
            )

            // The one thing users must know in messengers (the panel cannot
            // read another app's selection — no app can).
            addView(
                TextView(this@FloatingAssistantService).apply {
                    text = getString(R.string.assistant_hint)
                    textSize = 11f
                    setTextColor(Color.argb(200, 168, 176, 200))
                    setPadding(
                        (12 * density).toInt(),
                        (8 * density).toInt(),
                        (12 * density).toInt(),
                        (2 * density).toInt(),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (6 * density).toInt() }
                },
            )
        }

    /**
     * v0.5.2: panel header — small "Turnypt" caption on the left, a ✕
     * (40 dp touch target) on the right that collapses the panel WITHOUT
     * running anything. The bubble comes back; the service keeps running.
     */
    private fun panelHeader(density: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(),
                (4 * density).toInt(),
                (4 * density).toInt(),
                (4 * density).toInt(),
            )
            addView(
                TextView(this@FloatingAssistantService).apply {
                    text = getString(R.string.assistant_panel_title)
                    textSize = 12f
                    setTextColor(Color.argb(190, 168, 176, 200))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )
            addView(
                ImageView(this@FloatingAssistantService).apply {
                    setImageResource(R.drawable.ic_assistant_close)
                    contentDescription = getString(R.string.assistant_close_panel)
                    background = RippleDrawable(
                        ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                        },
                        null,
                    )
                    setPadding(
                        (11 * density).toInt(),
                        (11 * density).toInt(),
                        (11 * density).toInt(),
                        (11 * density).toInt(),
                    )
                    isClickable = true
                    setOnClickListener { collapsePanel() }
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * density).toInt(),
                        (40 * density).toInt(),
                    )
                },
            )
        }

    /** One labeled, tappable row of the panel (48dp touch target). */
    private fun panelRow(
        iconRes: Int,
        labelRes: Int,
        density: Float,
        onClick: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            // setFocusable(boolean) is overloaded by setFocusable(int) —
            // property syntax cannot pick one, so call the Boolean overload.
            setFocusable(true)

            val rippleColor = ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
            background = RippleDrawable(
                rippleColor,
                GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(Color.TRANSPARENT)
                },
                null,
            )
            setOnClickListener { onClick() }
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                (10 * density).toInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            )

            addView(
                ImageView(this@FloatingAssistantService).apply {
                    setImageResource(iconRes)
                    contentDescription = null
                    layoutParams = LinearLayout.LayoutParams(
                        (20 * density).toInt(),
                        (20 * density).toInt(),
                    ).apply { marginEnd = (12 * density).toInt() }
                },
            )
            addView(
                TextView(this@FloatingAssistantService).apply {
                    text = getString(labelRes)
                    textSize = 15f
                    setTextColor(Color.argb(245, 233, 236, 248))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                },
            )
        }

    /** Drag to move; a clean tap (no 10 px movement) toggles the panel. */
    private fun attachDragAndTap(bubbleView: View, params: WindowManager.LayoutParams) {
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (kotlin.math.abs(dx) > DRAG_SLOP ||
                            kotlin.math.abs(dy) > DRAG_SLOP
                        ) {
                            moved = true
                        }
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching {
                            windowManager?.updateViewLayout(root, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })
    }

    private fun togglePanel() {
        val bubbleView = bubble ?: return
        val panelView = panel ?: return
        val wasExpanded = expanded
        expanded = !wasExpanded
        bubbleView.visibility = if (expanded) View.GONE else View.VISIBLE
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun collapsePanel() {
        expanded = false
        bubble?.visibility = View.VISIBLE
        panel?.visibility = View.GONE
    }

    // ------------------------------------------------------------ actions

    private fun launchClipboardFlow(encrypt: Boolean) {
        collapsePanel()
        val intent = Intent(
            this,
            if (encrypt) EncryptActivity::class.java else DecryptActivity::class.java,
        )
            .putExtra(EXTRA_FROM_CLIPBOARD, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /**
     * v0.6.0: the image companion in picker mode — the sheet opens the
     * SAF image picker itself, so this row works from anywhere.
     */
    private fun launchImageFlow() {
        collapsePanel()
        val intent = Intent(this, ImageCipherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /**
     * v0.5.1: panic affordance — wipe every in-memory session key. Never
     * unlocks anything, touches nothing on disk; a Toast confirms it.
     */
    private fun lockAllSessions() {
        collapsePanel()
        scope.launch {
            runCatching {
                val wiped = messageCipher.lockAll()
                Toast.makeText(
                    this@FloatingAssistantService,
                    if (wiped > 0) {
                        getString(R.string.assistant_locked_all, wiped)
                    } else {
                        getString(R.string.assistant_locked_all_none)
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** v0.5.1: jump straight into the app's management UI. */
    private fun openApp() {
        collapsePanel()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /** "Turn off" on the panel: persist disabled, then stop the service. */
    private fun closeAssistant() {
        collapsePanel()
        scope.launch {
            runCatching { settings.setAssistantEnabled(false) }
            stopSelf()
        }
    }

    // ------------------------------------------------------------ notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.assistant_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_assistant_lock)
            .setContentTitle(getString(R.string.assistant_notification_title))
            .setContentText(getString(R.string.assistant_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "assistant"
        private const val NOTIFICATION_ID = 1042
        private const val DRAG_SLOP = 10f

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingAssistantService::class.java))
        }
    }
}
