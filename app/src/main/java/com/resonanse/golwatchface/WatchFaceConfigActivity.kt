package com.resonanse.golwatchface

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import kotlinx.coroutines.launch

/**
 * On-watch editor, reached with long-press → Customize.
 *
 * One button per style setting (each tap steps to the next option, so however many
 * themes or rules exist, the UI does not change) and one per complication slot.
 *
 * NOTE: the data-source chooser only opens if the app declares
 * `com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA`; without it the
 * runtime permission is auto-denied and the slot buttons silently do nothing.
 */
class WatchFaceConfigActivity : ComponentActivity() {

    private companion object {
        val SLOTS = listOf(
            "TOP"    to LifeWatchFaceService.TOP_ID,
            "LOWER L" to LifeWatchFaceService.BL_ID,
            "LOWER R" to LifeWatchFaceService.BR_ID,
        )
        val STYLES = listOf(
            "THEME"  to "theme",
            "RULE"   to "rule",
            "MOTION" to "motion",
        )
        val ACCENT = Color.rgb(90, 255, 130)
        val CHROME = Color.rgb(10, 24, 14)
    }

    private var editorSession: EditorSession? = null
    private val slotButtons = mutableMapOf<Int, Button>()
    private val styleButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val session = EditorSession.createOnWatchEditorSession(this@WatchFaceConfigActivity)
            editorSession = session
            buildUI()
            session.complicationsDataSourceInfo.collect { info ->
                for ((label, id) in SLOTS) {
                    slotButtons[id]?.text = "[ $label ]\n${info[id]?.name ?: "not set"}"
                }
            }
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 40, 24, 40)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "GAME OF LIFE — EDIT"
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
        }, lp(14))

        for ((label, id) in STYLES) {
            val btn = chrome("[ $label ]") { cycleStyle(id) }
            styleButtons[id] = btn
            root.addView(btn, lp(8))
        }
        refreshStyleLabels()

        for ((label, id) in SLOTS) {
            val btn = chrome("[ $label ]") {
                lifecycleScope.launch { editorSession?.openComplicationDataSourceChooser(id) }
            }
            slotButtons[id] = btn
            root.addView(btn, lp(8))
        }

        root.addView(TextView(this).apply {
            text = "Tap the face to drop\nan R-pentomino"
            textSize = 11f
            setTextColor(Theme.withAlpha(ACCENT, 0x99))
            gravity = Gravity.CENTER
        }, lp(0))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })
    }

    private fun chrome(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        setLineSpacing(0f, 0.95f)
        setTextColor(ACCENT)
        setBackgroundColor(CHROME)
        setPadding(8, 8, 8, 8)
        setOnClickListener { onClick() }
    }

    private fun listSetting(id: String): ListUserStyleSetting? =
        editorSession?.userStyleSchema?.userStyleSettings
            ?.filterIsInstance<ListUserStyleSetting>()
            ?.firstOrNull { it.id.value == id }

    private fun currentOption(id: String): ListUserStyleSetting.ListOption? {
        val setting = listSetting(id) ?: return null
        return editorSession?.userStyle?.value?.get(setting) as? ListUserStyleSetting.ListOption
    }

    private fun refreshStyleLabels() {
        for ((label, id) in STYLES) {
            styleButtons[id]?.text = "[ $label ]\n${currentOption(id)?.displayName ?: "—"}"
        }
    }

    private fun cycleStyle(id: String) {
        val session = editorSession ?: return
        val setting = listSetting(id) ?: return
        val options = setting.options.filterIsInstance<ListUserStyleSetting.ListOption>()
        if (options.isEmpty()) return
        val idx = options.indexOfFirst { it.id == currentOption(id)?.id }
        val next = options[(idx + 1).mod(options.size)]
        session.userStyle.value = session.userStyle.value.toMutableUserStyle()
            .apply { set(setting, next) }
            .toUserStyle()
        refreshStyleLabels()
    }

    private fun lp(marginBottom: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, marginBottom) }

    // Deliberately no onDestroy/close(): createOnWatchEditorSession installs its own
    // lifecycle observer that closes the session on ON_DESTROY and commits the edit.
    // Closing it a second time throws out of onDestroy and kills the process
    // mid-commit, which silently rolls the choice back.
}
