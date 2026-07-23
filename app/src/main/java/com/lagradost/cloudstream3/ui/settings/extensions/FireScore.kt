package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlin.math.roundToInt

/**
 * The flame badge shown against an extension's TrustScore (0..100, see VotingApi).
 *
 * Like Rotten Tomatoes' badges, each tier gets its own *silhouette*, not just
 * a colour — filled while "burning", hollow once burnt out, blue-fire gradient
 * for the top tier. Tint alone would be illegible at badge size and invisible
 * to colour-blind users.
 */
enum class FireScore(
    @DrawableRes val iconRes: Int,
    private val descriptionRes: Int,
) {
    /** The hottest tier; wears the blue-fire gradient flame. */
    BLUE_FIRE(R.drawable.fire_blue, R.string.extension_rating_certified_description),

    /** Solidly rated. */
    HOT(R.drawable.fire_solid, R.string.extension_rating_hot_description),

    /** Burnt out — the "rotten" tier. */
    COLD(R.drawable.fire_outline, R.string.extension_rating_cold_description),

    /** Too few votes for the canister to report a score. */
    NEW(R.drawable.fire_outline, R.string.extension_rating_new_description);

    /**
     * Tint for [iconRes], or null to leave the drawable's own colours alone.
     * Separate from [textColor] because the blue-fire flame is already a
     * cyan -> indigo gradient, and tinting it would flatten that to one colour.
     */
    @ColorInt
    fun iconTint(context: Context): Int? =
        if (this == BLUE_FIRE) null else textColor(context)

    @ColorInt
    fun textColor(context: Context): Int = when (this) {
        BLUE_FIRE -> ContextCompat.getColor(context, R.color.fireScoreBlue)
        HOT -> ContextCompat.getColor(context, R.color.fireScoreHot)
        COLD -> ContextCompat.getColor(context, R.color.fireScoreCold)
        NEW -> context.colorFromAttribute(R.attr.grayTextColor)
    }

    fun label(context: Context, score: Double?): String = when {
        score == null -> context.getString(R.string.extension_rating_new)
        else -> context.getString(R.string.extension_rating_percent, score.roundToInt())
    }

    fun description(context: Context, score: Double?): String = when {
        score == null -> context.getString(descriptionRes)
        else -> context.getString(descriptionRes, score.roundToInt())
    }

    companion object {
        /**
         * TrustScore cut-offs: 0–50 is Cold, 51–79 is Hot, 80+ is Blue Fire.
         *
         * Half-point boundaries so the tier lines up with the rounded percentage
         * the user actually sees — a badge that reads "51%" is Hot, "80%" is Blue
         * Fire, with no off-by-one between the number and the flame.
         */
        private const val BLUE_FIRE_MIN = 79.5
        private const val HOT_MIN = 50.5

        /** Badge flame size in the extension list rows. */
        val LIST_ICON_DP = 14

        /**
         * Score for an extension the canister has no rating for. A missing
         * score is treated as a neutral 50% everywhere — badge and sort alike —
         * rather than penalised as 0% or singled out as "New".
         */
        const val DEFAULT_SCORE = 50.0

        fun of(score: Double?): FireScore = when {
            score == null -> NEW
            score >= BLUE_FIRE_MIN -> BLUE_FIRE
            score >= HOT_MIN -> HOT
            else -> COLD
        }

        /**
         * Renders [score] into [view] as "<flame> 92%".
         *
         * The flame is a compound drawable rather than its own ImageView: the
         * badge lives in a row that is already several views deep and is bound
         * for every item in the list.
         */
        fun bind(view: TextView, score: Double?, iconDp: Int = LIST_ICON_DP, showText: Boolean = true) {
            val context = view.context ?: return
            val tier = of(score)

            // The list rows want the flame silhouette alone (tier at a glance);
            // the details sheet keeps the "<flame> 92%" label. Either way the
            // description stays for screen readers.
            view.text = if (showText) tier.label(context, score) else ""
            view.contentDescription = tier.description(context, score)
            view.setTextColor(tier.textColor(context))

            val icon: Drawable? = ContextCompat.getDrawable(context, tier.iconRes)?.mutate()
            // The source flames are 200dp artwork; compound drawables render at
            // their intrinsic size, so bounds have to be set explicitly.
            icon?.setBounds(0, 0, iconDp.toPx, iconDp.toPx)
            view.setCompoundDrawablesRelative(icon, null, null, null)
            // Always assigned, never skipped when null: these views are recycled
            // and a leftover tint would repaint the next tier's flame.
            TextViewCompat.setCompoundDrawableTintList(
                view,
                tier.iconTint(context)?.let { ColorStateList.valueOf(it) }
            )
        }
    }
}
