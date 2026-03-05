package com.blazinghotcode.blazingmusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val sections: List<Char> = buildList {
        add('#')
        addAll('A'..'Z')
    }
    private var onSectionSelected: ((Char) -> Unit)? = null
    private var activeSection: Char? = null
    private var availableSections: Set<Char> = emptySet()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
        color = ContextCompat.getColor(context, R.color.text_muted)
    }

    fun setOnSectionSelectedListener(listener: (Char) -> Unit) {
        onSectionSelected = listener
    }

    fun setAvailableSections(sections: Set<Char>) {
        availableSections = sections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height <= 0 || width <= 0 || sections.isEmpty()) return
        val rowHeight = height.toFloat() / sections.size.toFloat()
        val centerX = width / 2f
        for ((index, section) in sections.withIndex()) {
            val centerY = rowHeight * index + rowHeight / 2f
            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            val isActive = activeSection == section
            val isAvailable = availableSections.contains(section)
            textPaint.color = when {
                isActive -> ContextCompat.getColor(context, R.color.accent_lavender)
                isAvailable -> ContextCompat.getColor(context, R.color.text_primary)
                else -> ContextCompat.getColor(context, R.color.text_muted)
            }
            textPaint.alpha = if (isAvailable || isActive) 255 else 140
            textPaint.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(section.toString(), centerX, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                activeSection = null
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeSection = null
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun handleTouch(y: Float) {
        if (height <= 0) return
        val rowHeight = height.toFloat() / sections.size.toFloat()
        val index = (y / rowHeight).toInt().coerceIn(0, sections.lastIndex)
        val section = sections[index]
        if (section != activeSection) {
            activeSection = section
            invalidate()
        }
        onSectionSelected?.invoke(section)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )
    }
}
