package com.tvcast.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.tvcast.app.R

/**
 * One row in the [SettingsActivity] screen: title + summary + a [Switch] that gains focus on TV
 * D-pad. Built as a custom [LinearLayout] so we don't depend on the Preference framework's quirks
 * around Leanback theming.
 */
class SettingRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    private val titleView: TextView
    private val summaryView: TextView
    private val switchView: Switch

    init {
        orientation = HORIZONTAL
        setPadding(16, 16, 16, 16)
        isFocusable = true
        isClickable = true
        setBackgroundResource(android.R.drawable.list_selector_background)
        LayoutInflater.from(context).inflate(R.layout.view_setting_row, this, true)
        titleView = findViewById(R.id.row_title)
        summaryView = findViewById(R.id.row_summary)
        switchView = findViewById(R.id.row_switch)
        setOnClickListener { switchView.toggle() }
    }

    fun bind(title: CharSequence, summary: CharSequence, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        titleView.text = title
        summaryView.text = summary
        switchView.setOnCheckedChangeListener(null)
        switchView.isChecked = checked
        switchView.setOnCheckedChangeListener { _, value -> onCheckedChange(value) }
    }
}
