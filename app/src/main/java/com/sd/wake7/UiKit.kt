package com.sd.wake7

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * Small, dependency-free UI helpers shared by MainActivity and AlarmActivity.
 * Wake 7 builds its UI in plain Kotlin (no XML layouts, no external UI
 * library), so these helpers keep spacing in real dp and give buttons/cards
 * a consistent, professional look without touching the Gradle build.
 */

object Palette {
    const val BACKGROUND = "#F4F2F0"
    const val SURFACE = "#FFFFFF"
    const val TEXT_PRIMARY = "#1B1B1F"
    const val TEXT_SECONDARY = "#6F6F76"
    const val ACCENT = "#C62828"
    const val ACCENT_SOFT = "#F6DADA"
}

fun Int.dp(context: Context): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), context.resources.displayMetrics).toInt()

fun Float.dpF(context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, context.resources.displayMetrics)

fun pillShape(context: Context, fillColor: Int, radiusDp: Int = 16, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.dp(context).toFloat()
        setColor(fillColor)
        if (strokeColor != null) setStroke(strokeWidthDp.dp(context), strokeColor)
    }

private fun rippleOver(shape: GradientDrawable, rippleColor: Int): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape)

fun buildPrimaryButton(context: Context, label: String): Button = Button(context).apply {
    text = label
    setAllCaps(false)
    textSize = 16f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.WHITE)
    setPadding(0, 0, 0, 0)
    stateListAnimator = null
    background = rippleOver(pillShape(context, Color.parseColor(Palette.ACCENT), 16), Color.parseColor("#40FFFFFF"))
}

fun buildSecondaryButton(context: Context, label: String): Button = Button(context).apply {
    text = label
    setAllCaps(false)
    textSize = 15f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.parseColor(Palette.ACCENT))
    setPadding(0, 0, 0, 0)
    stateListAnimator = null
    background = rippleOver(pillShape(context, Color.WHITE, 16, Color.parseColor(Palette.ACCENT), 2), Color.parseColor("#20C62828"))
}

fun buildCard(context: Context, radiusDp: Int = 22): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = pillShape(context, Color.parseColor(Palette.SURFACE), radiusDp)
    elevation = 3f.dpF(context)
}

fun matchWrap(context: Context, topDp: Int = 0, bottomDp: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = topDp.dp(context); bottomMargin = bottomDp.dp(context)
    }

fun matchHeight(context: Context, heightDp: Int, topDp: Int = 0, bottomDp: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightDp.dp(context)).apply {
        topMargin = topDp.dp(context); bottomMargin = bottomDp.dp(context)
    }
