package org.satelliteeavesdropper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test fun coverPhoneAndNarrowSplitScreenUseBottomNavigation() {
        assertEquals(AdaptiveNavigation.BOTTOM_BAR, adaptiveNavigation(320f, 740f))
        assertEquals(AdaptiveNavigation.BOTTOM_BAR, adaptiveNavigation(411f, 800f))
        assertEquals(AdaptiveNavigation.BOTTOM_BAR, adaptiveNavigation(420f, 390f))
    }

    @Test fun unfoldedTabletAndShortLandscapeUseRail() {
        assertEquals(AdaptiveNavigation.RAIL, adaptiveNavigation(673f, 841f))
        assertEquals(AdaptiveNavigation.RAIL, adaptiveNavigation(1280f, 800f))
        assertEquals(AdaptiveNavigation.RAIL, adaptiveNavigation(740f, 320f))
        assertEquals(AdaptiveNavigation.RAIL, adaptiveNavigation(500f, 390f))
    }

    @Test fun resizingChangesNavigationWithoutADeviceModelCheck() {
        val widths = listOf(320f, 673f, 420f)
        assertEquals(listOf(AdaptiveNavigation.BOTTOM_BAR, AdaptiveNavigation.RAIL, AdaptiveNavigation.BOTTOM_BAR),
            widths.map { adaptiveNavigation(it, 800f) })
    }

    @Test fun flatWindowUsesEntireAvailableArea() {
        assertEquals(AdaptiveRegion(0f, 0f, 673f, 841f), adaptiveContentRegion(673f, 841f, emptyList()))
    }

    @Test fun separatingVerticalCreaseKeepsControlsOnOneSide() {
        val fold = AdaptiveFold(AdaptiveRegion(400f, 0f, 400f, 900f), true)
        val pane = adaptiveContentRegion(800f, 900f, listOf(fold))
        assertEquals(AdaptiveRegion(0f, 0f, 392f, 900f), pane)
        assertTrue(pane.right < fold.bounds.left)
    }

    @Test fun realWidthHingeChoosesLargerUnobscuredPane() {
        val fold = AdaptiveFold(AdaptiveRegion(360f, 0f, 384f, 900f), true)
        assertEquals(AdaptiveRegion(392f, 0f, 900f, 900f), adaptiveContentRegion(900f, 900f, listOf(fold)))
    }

    @Test fun tabletopFoldLeavesHorizontalHingeUntouched() {
        val fold = AdaptiveFold(AdaptiveRegion(0f, 390f, 800f, 414f), false)
        assertEquals(AdaptiveRegion(0f, 422f, 800f, 900f), adaptiveContentRegion(800f, 900f, listOf(fold)))
    }

    @Test fun foldOutsideResizedWindowDoesNotDiscardVisibleContent() {
        val fold = AdaptiveFold(AdaptiveRegion(650f, 0f, 650f, 900f), true)
        assertEquals(AdaptiveRegion(0f, 0f, 320f, 740f), adaptiveContentRegion(320f, 740f, listOf(fold)))
    }

    @Test fun twoSeparatingHingesNeverChooseARegionSpanningEitherHinge() {
        val folds = listOf(
            AdaptiveFold(AdaptiveRegion(400f, 0f, 410f, 900f), true),
            AdaptiveFold(AdaptiveRegion(820f, 0f, 830f, 900f), true),
        )
        val pane = adaptiveContentRegion(1250f, 900f, folds)
        assertTrue(folds.all { pane.right < it.bounds.left || pane.left > it.bounds.right })
    }
}
