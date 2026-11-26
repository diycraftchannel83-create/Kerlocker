package com.example.ui.components

import kotlin.math.abs

enum class SlideNavigationAction {
    NONE,
    NEXT,
    PREVIOUS
}

enum class SlideAxis {
    HORIZONTAL,
    VERTICAL
}

data class SlideGestureOutcome(
    val action: SlideNavigationAction,
    val axis: SlideAxis
)

object GalleryNavigationHelper {

    fun resolveSwipeAction(
        dragX: Float,
        dragY: Float,
        thresholdPx: Float
    ): SlideGestureOutcome {
        val absX = abs(dragX)
        val absY = abs(dragY)
        return if (absX >= absY) {
            when {
                dragX > thresholdPx -> SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.HORIZONTAL)
                dragX < -thresholdPx -> SlideGestureOutcome(SlideNavigationAction.PREVIOUS, SlideAxis.HORIZONTAL)
                else -> SlideGestureOutcome(SlideNavigationAction.NONE, SlideAxis.HORIZONTAL)
            }
        } else {
            when {
                dragY < -thresholdPx -> SlideGestureOutcome(SlideNavigationAction.NEXT, SlideAxis.VERTICAL)
                dragY > thresholdPx -> SlideGestureOutcome(SlideNavigationAction.PREVIOUS, SlideAxis.VERTICAL)
                else -> SlideGestureOutcome(SlideNavigationAction.NONE, SlideAxis.VERTICAL)
            }
        }
    }

    fun nextIndex(currentIndex: Int, totalCount: Int): Int {
        if (totalCount <= 1) return currentIndex.coerceAtLeast(0)
        val safeCurrent = currentIndex.coerceIn(0, totalCount - 1)
        return (safeCurrent + 1) % totalCount
    }

    fun previousIndex(currentIndex: Int, totalCount: Int): Int {
        if (totalCount <= 1) return currentIndex.coerceAtLeast(0)
        val safeCurrent = currentIndex.coerceIn(0, totalCount - 1)
        return (safeCurrent - 1 + totalCount) % totalCount
    }

    data class FittedMediaSize(
        val width: Float,
        val height: Float
    )

    fun calculateFittedPreviewSize(
        containerWidth: Float,
        containerHeight: Float,
        mediaWidth: Int,
        mediaHeight: Int,
        allowUpscale: Boolean = true
    ): FittedMediaSize {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return FittedMediaSize(0f, 0f)
        }
        if (mediaWidth <= 0 || mediaHeight <= 0) {
            return FittedMediaSize(containerWidth, containerHeight)
        }
        val mW = mediaWidth.toFloat()
        val mH = mediaHeight.toFloat()
        val scale = if (allowUpscale) {
            minOf(containerWidth / mW, containerHeight / mH)
        } else {
            minOf(containerWidth / mW, containerHeight / mH, 1f)
        }
        return FittedMediaSize(
            width = (mW * scale).coerceAtLeast(1f),
            height = (mH * scale).coerceAtLeast(1f)
        )
    }
}
