package com.wndenis.snipsnap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun rememberScheduleCalendarState(
    referenceDateTime: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS),
    onDateTimeSelected: (LocalDateTime) -> Unit = {}
): ScheduleCalendarState {
    val coroutineScope = rememberCoroutineScope()
    return remember(coroutineScope) {
        ScheduleCalendarState(
            referenceDateTime = referenceDateTime,
            onDateTimeSelected = onDateTimeSelected,
            coroutineScope = coroutineScope,
        )
    }
}

class ScheduleCalendarState(
    referenceDateTime: LocalDateTime,
    private val onDateTimeSelected: (LocalDateTime) -> Unit,
    private val coroutineScope: CoroutineScope
) {
    val startDateTime: LocalDateTime by derivedStateOf {
        referenceDateTime.plusSeconds(secondsOffset.value)
    }

    val endDateTime: LocalDateTime by derivedStateOf {
        startDateTime.plusSeconds(this.viewSpanSeconds.value)
    }

    val spanType: SpanType by derivedStateOf {
        spanToType(viewSpanSeconds.value)
    }

    fun viewSpanSeconds(): Long {
        return viewSpanSeconds.value
    }

    private val viewSpanSeconds = Animatable(ChronoUnit.DAYS.duration.seconds, LongToVector)
    private val secondsOffset = Animatable(0L, LongToVector)
    private val width = mutableStateOf(1)

    private var canUpdateView = true
    var canScroll = mutableStateOf(true)

    internal fun updateView(newViewSpanSeconds: Long, newWidth: Int? = null) {
        if (!canUpdateView)
            return
        newWidth?.let { this.width.value = newWidth }

        val currentViewSpanSeconds = viewSpanSeconds.value
        coroutineScope.launch {
            viewSpanSeconds.animateTo(newViewSpanSeconds)
        }
        coroutineScope.launch {
            if (newViewSpanSeconds != currentViewSpanSeconds) {
                updateAnchors(newViewSpanSeconds)
            }
        }
    }

    internal val scrollableState = ScrollableState {
        coroutineScope.launch {
            secondsOffset.snapTo(secondsOffset.value - it.toSeconds())
        }
        it
    }

    fun scrollToNow(newSpan: Long) {
        coroutineScope.launch {
            canUpdateView = false
            secondsOffset.animateTo(-newSpan / 4)
        }
        coroutineScope.launch {
            canUpdateView = false
            viewSpanSeconds.animateTo(newSpan)
            canUpdateView = true
            // wndenis: sorry for this spaghetti, I had no time to deal with it
            updateView(newSpan)
        }
    }

    private val secondsInPx by derivedStateOf {
        this.viewSpanSeconds.value.toFloat() / width.value.toFloat()
    }

    private fun Float.toSeconds(): Long {
        return (this * secondsInPx).roundToLong()
    }

    private fun Long.toPx(): Float {
        return this / secondsInPx
    }

    internal val scrollFlingBehavior = object : FlingBehavior {
        val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            val endPosition = decay.calculateTargetValue(0f, -initialVelocity)
            flingToNearestAnchor(secondsOffset.value.toPx() + endPosition)
            return 0f
        }
    }

    internal val visibleHours by derivedStateOf {
        val startHour = startDateTime.truncatedTo(ChronoUnit.HOURS)
        val endHour = endDateTime.truncatedTo(ChronoUnit.HOURS).plusHours(1)

        if (anchorRangeSeconds == 24 * 3600L) {
            emptyList()
        } else {
            startHour.between(endHour) { plusHours(1L) }.filter {
                it.hour % (anchorRangeSeconds / 3600) == 0L && it.hour != 0
            }.toList()
        }
    }

    private var anchorRangeSeconds by mutableStateOf(Long.MAX_VALUE)
    private var anchorRangePx by mutableStateOf(Float.MAX_VALUE)
    private suspend fun updateAnchors(viewSpanInSeconds: Long) {
        anchorRangeSeconds = if (viewSpanInSeconds > 24 * 3600) {
            24 * 3600
        } else if (viewSpanInSeconds <= 24 * 3600 && viewSpanInSeconds > 12 * 3600) {
            6 * 3600
        } else if (viewSpanInSeconds <= 12 * 3600 && viewSpanInSeconds > 6 * 3600) {
            3 * 3600
        } else if (viewSpanInSeconds <= 6 * 3600 && viewSpanInSeconds > 3 * 3600) {
            2 * 3600
        } else {
            3600
        }
        anchorRangePx = anchorRangeSeconds.toPx()
        flingToNearestAnchor(secondsOffset.value.toPx())
    }

    private suspend fun flingToNearestAnchor(target: Float) {
        val nearestAnchor = target - (target.absRem(anchorRangePx))
        val nearestAnchor2 = nearestAnchor + anchorRangePx

        val newAnchoredPosition =
            (abs(target - nearestAnchor) to abs(target - nearestAnchor2)).let {
                if (it.first > it.second) nearestAnchor2 else nearestAnchor
            }
        secondsOffset.animateTo(newAnchoredPosition.toSeconds())
        onDateTimeSelected(startDateTime)
    }

    internal fun offsetFraction(localDateTime: LocalDateTime): Float {
        return ChronoUnit.SECONDS.between(startDateTime, localDateTime)
            .toFloat() / (viewSpanSeconds.value).toFloat()
    }

    internal fun widthAndOffsetForEvent(
        start: LocalDateTime,
        end: LocalDateTime,
        totalWidth: Int
    ): Pair<Int, Int> {
        val startOffsetPercent = offsetFraction(start).coerceIn(0f, 1f)
        val endOffsetPercent = offsetFraction(end).coerceIn(0f, 1f)

        val width = ((endOffsetPercent - startOffsetPercent) * totalWidth).toInt() + 1
        val offsetX = (startOffsetPercent * totalWidth).toInt()
        return width to offsetX
    }
}

enum class SpanType {
    LESSER, WEEK, TWO_WEEK, TWO_WEEK_A_HALF, MONTH, THREE_MONTH, SIX_MONTH, YEAR, TWO_YEAR, BIGGER
}

private fun spanToType(span: Long): SpanType {
    when {
        span <= WEEK_SEC / 2 -> {
            return SpanType.LESSER
        }
        span <= WEEK_SEC -> {
            return SpanType.WEEK
        }
        span <= WEEK_SEC * 2 -> {
            return SpanType.TWO_WEEK
        }
        span <= WEEK_SEC * 2 + WEEK_SEC / 2 -> {
            return SpanType.TWO_WEEK_A_HALF
        }
        span <= MONTH_SEC -> {
            return SpanType.MONTH
        }
        span <= MONTH_SEC * 3 -> {
            return SpanType.THREE_MONTH
        }
        span <= MONTH_SEC * 6 -> {
            return SpanType.SIX_MONTH
        }
        span <= YEAR_SEC -> {
            return SpanType.YEAR
        }
        span <= YEAR_SEC * 2 -> {
            return SpanType.TWO_YEAR
        }
        else -> return SpanType.BIGGER
    }
}

private val LongToVector: TwoWayConverter<Long, AnimationVector1D> =
    TwoWayConverter({ AnimationVector1D(it.toFloat()) }, { it.value.toLong() })

private fun Float.absRem(modular: Float): Float {
    return ((this % modular) + modular) % modular
}
