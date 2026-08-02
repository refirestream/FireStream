package com.lagradost.cloudstream3.metaproviders

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/**
 * Enable/disable and ordering for [TmdbFireProvider]'s home rows.
 *
 * The catalogue itself lives in the provider; here we only remember, keyed by each row's stable
 * [HomeCategory.id], which rows the user turned off and what order they put them in. Ids are the
 * contract — never rename one, or a user's saved choice for that row is silently dropped (see
 * [order], which discards unknown ids).
 */
object TmdbFireHomeSettings {
    private const val ORDER_KEY = "tmdbfire_home_category_order"
    private fun enabledKey(id: String) = "tmdbfire_home_category_enabled_$id"

    /** Rows default to on, so a freshly added category shows up without the user opting in. */
    fun isEnabled(id: String): Boolean = getKey<Boolean>(enabledKey(id)) ?: true

    private fun setEnabled(id: String, value: Boolean) = setKey(enabledKey(id), value)

    /**
     * Saved order, reconciled against [defaultOrder]: saved ids that no longer exist are dropped and
     * newly added ones fall in at the end, so the app's own additions surface without wiping the
     * user's arrangement. No saved order yet -> the provider's own order.
     */
    fun order(defaultOrder: List<String>): List<String> {
        val saved = getKey<String>(ORDER_KEY)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: return defaultOrder
        val known = saved.filter { it in defaultOrder }
        return known + (defaultOrder - known.toSet())
    }

    private fun saveOrder(order: List<String>) = setKey(ORDER_KEY, order.joinToString(","))

    /** The rows to actually build, in the user's order, minus the ones they disabled. */
    fun orderedEnabledIds(defaultOrder: List<String>): List<String> =
        order(defaultOrder).filter { isEnabled(it) }

    /**
     * A category as the dialog needs to show it: its stable [id] and the [title] the row carries.
     */
    data class Entry(val id: String, val title: String)

    /**
     * Shows the reorder/enable dialog. [defaults] is every category in the provider's own order;
     * [onSaved] runs after the user saves so the caller can reload the home.
     */
    fun show(context: Context, defaults: List<Entry>, onSaved: () -> Unit) {
        val defaultOrder = defaults.map { it.id }
        val byId = defaults.associateBy { it.id }

        // Working copies — nothing is persisted until Save.
        val workingOrder = order(defaultOrder).toMutableList()
        val workingEnabled = defaultOrder.associateWith { isEnabled(it) }.toMutableMap()

        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        fun rebuild() {
            rows.removeAllViews()
            workingOrder.forEachIndexed { index, id ->
                val entry = byId[id] ?: return@forEachIndexed
                rows.addView(
                    buildRow(
                        context = context,
                        title = entry.title,
                        enabled = workingEnabled[id] ?: true,
                        canMoveUp = index > 0,
                        canMoveDown = index < workingOrder.lastIndex,
                        onToggle = { workingEnabled[id] = it },
                        onMoveUp = {
                            workingOrder.add(index - 1, workingOrder.removeAt(index)); rebuild()
                        },
                        onMoveDown = {
                            workingOrder.add(index + 1, workingOrder.removeAt(index)); rebuild()
                        },
                    )
                )
            }
        }
        rebuild()

        val scroll = NestedScrollView(context).apply {
            val pad = context.dp(8)
            setPadding(context.dp(16), pad, context.dp(16), pad)
            addView(rows)
        }

        AlertDialog.Builder(context)
            .setTitle("Home categories")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                saveOrder(workingOrder)
                workingEnabled.forEach { (id, value) -> setEnabled(id, value) }
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                setKey(ORDER_KEY, null as String?)
                defaultOrder.forEach { setKey(enabledKey(it), null as Boolean?) }
                onSaved()
            }
            .show()
    }

    private fun buildRow(
        context: Context,
        title: String,
        enabled: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onToggle: (Boolean) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val v = context.dp(6)
        setPadding(0, v, 0, v)

        addView(TextView(context).apply {
            text = title
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        fun arrow(symbol: String, active: Boolean, action: () -> Unit) = TextView(context).apply {
            text = symbol
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = if (active) 1f else 0.3f
            val p = context.dp(10)
            setPadding(p, context.dp(4), p, context.dp(4))
            isClickable = active
            isFocusable = active
            if (active) setOnClickListener { action() }
        }
        addView(arrow("▲", canMoveUp, onMoveUp))
        addView(arrow("▼", canMoveDown, onMoveDown))

        addView(SwitchMaterial(context).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _, value -> onToggle(value) }
        })
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
