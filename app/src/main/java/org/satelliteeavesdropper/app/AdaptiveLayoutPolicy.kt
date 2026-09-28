package org.satelliteeavesdropper.app

/** Window-local dp coordinates. Keeping the policy independent of Android makes resize rules testable. */
internal data class AdaptiveRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal data class AdaptiveFold(val bounds: AdaptiveRegion, val vertical: Boolean)

internal enum class AdaptiveNavigation { BOTTOM_BAR, RAIL }

internal fun adaptiveNavigation(widthDp: Float, heightDp: Float): AdaptiveNavigation =
    if (widthDp >= 600f || (widthDp >= 480f && heightDp < 480f)) AdaptiveNavigation.RAIL
    else AdaptiveNavigation.BOTTOM_BAR

/**
 * An occluding hinge or a separating half-open fold is a hard pane boundary. Put one logical
 * screen in the largest unobscured pane rather than drawing controls across that boundary.
 * Flat, non-occluding creases are omitted by the caller and can use the whole unfolded window.
 */
internal fun adaptiveContentRegion(widthDp: Float, heightDp: Float, folds: List<AdaptiveFold>): AdaptiveRegion {
    val width = widthDp.coerceAtLeast(0f)
    val height = heightDp.coerceAtLeast(0f)
    var regions = listOf(AdaptiveRegion(0f, 0f, width, height))
    folds.forEach { fold ->
        val bounds = fold.bounds
        if (!listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) return@forEach
        regions = regions.flatMap { region ->
            val crosses = if (fold.vertical)
                bounds.bottom > region.top && bounds.top < region.bottom &&
                    bounds.right >= region.left && bounds.left <= region.right
            else bounds.right > region.left && bounds.left < region.right &&
                bounds.bottom >= region.top && bounds.top <= region.bottom
            if (!crosses) listOf(region) else {
                // Leave a little room beside a crease even when its reported width is zero.
                val gap = 8f
                val candidates = if (fold.vertical) listOf(
                    region.copy(right = (bounds.left - gap).coerceIn(region.left, region.right)),
                    region.copy(left = (bounds.right + gap).coerceIn(region.left, region.right)),
                ) else listOf(
                    region.copy(bottom = (bounds.top - gap).coerceIn(region.top, region.bottom)),
                    region.copy(top = (bounds.bottom + gap).coerceIn(region.top, region.bottom)),
                )
                candidates.filter { it.width > 0f && it.height > 0f }
            }
        }
    }
    // Prefer a pane with enough width for forms, then its usable area. A tie uses the first pane.
    return regions.maxByOrNull { it.width * it.height * if (it.width < 240f) 0.1f else 1f }
        ?: AdaptiveRegion(0f, 0f, width, height)
}
