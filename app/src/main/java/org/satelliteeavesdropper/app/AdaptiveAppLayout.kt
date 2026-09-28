package org.satelliteeavesdropper.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.flowOf

internal data class OrbitNavigationItem(val label: String, val symbol: String)

private val LocalOrbitWindowLayoutInfo = compositionLocalOf<WindowLayoutInfo?> { null }

/**
 * One Activity stream owns the current posture. Dialogs inherit this value immediately instead
 * of waiting for a new event after opening; window-layout flows need not replay prior events.
 */
@Composable
internal fun OrbitAdaptiveWindow(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val layoutFlow = remember(activity, context) {
        activity?.let { WindowInfoTracker.getOrCreate(context).windowLayoutInfo(it) }
            ?: flowOf<WindowLayoutInfo?>(null)
    }
    val layout by layoutFlow.collectAsStateWithLifecycle(initialValue = null)
    CompositionLocalProvider(LocalOrbitWindowLayoutInfo provides layout, content = content)
}

/** Recalculates from actual window constraints and live posture, including split screen and IME. */
@Composable
internal fun OrbitAdaptiveScaffold(
    title: String,
    destinations: List<OrbitNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    catalogStatus: String,
    statusColor: Color,
    orbitCount: Int,
    onAbout: () -> Unit,
    maxContentWidth: Dp = 840.dp,
    foldOverride: List<AdaptiveFold>? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .onGloballyPositioned { rootOrigin = it.positionInWindow() },
    ) {
        val folds = foldOverride ?: rememberWindowFolds(rootOrigin)
        val pane = adaptiveContentRegion(maxWidth.value, maxHeight.value, folds)
        val navigation = adaptiveNavigation(pane.width, pane.height)
        val shortWindow = pane.height < 480f
        val railWidth = if (density.fontScale > 1.3f) 104.dp else 88.dp
        Box(
            Modifier.offset(pane.left.dp, pane.top.dp).requiredSize(pane.width.dp, pane.height.dp)
                .testTag("app-safe-pane"),
        ) {
            Row(Modifier.fillMaxSize()) {
                if (navigation == AdaptiveNavigation.RAIL) NavigationRail(
                    modifier = Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState())
                        .testTag("app-navigation-rail"),
                    containerColor = OrbitColors.surface,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    destinations.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            icon = { Text(item.symbol) },
                            label = { Text(item.label) },
                        )
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        AdaptiveHeader(title, catalogStatus, statusColor, orbitCount, onAbout, shortWindow)
                    },
                    bottomBar = {
                        if (navigation == AdaptiveNavigation.BOTTOM_BAR) NavigationBar(
                            modifier = Modifier.testTag("app-navigation-bar"),
                            containerColor = OrbitColors.surface,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            destinations.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = index == selectedIndex,
                                    onClick = { onSelect(index) },
                                    icon = { Text(item.symbol) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().fillMaxHeight()
                            .testTag("app-content")) {
                            content(PaddingValues(0.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The popup and its buttons stay in an unobscured pane when a fold changes while it is open. */
@Composable
internal fun OrbitAdaptiveDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .onGloballyPositioned { coordinates ->
                // The dialog has its own Window; folding-feature coordinates belong to the
                // Activity Window. Screen coordinates bridge the two origins accurately.
                val screenOrigin = IntArray(2)
                val windowOrigin = IntArray(2)
                activity?.window?.decorView?.getLocationOnScreen(screenOrigin)
                activity?.window?.decorView?.getLocationInWindow(windowOrigin)
                rootOrigin = coordinates.positionOnScreen() - Offset(
                    (screenOrigin[0] - windowOrigin[0]).toFloat(),
                    (screenOrigin[1] - windowOrigin[1]).toFloat(),
                )
            }) {
            val pane = adaptiveContentRegion(maxWidth.value, maxHeight.value, rememberWindowFolds(rootOrigin))
            Box(Modifier.offset(pane.left.dp, pane.top.dp).requiredSize(pane.width.dp, pane.height.dp)
                .padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                        .heightIn(max = (pane.height.dp - 32.dp).coerceAtLeast(1.dp))
                        .testTag("adaptive-dialog"),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        title?.let { slot -> ProvideTextStyle(MaterialTheme.typography.headlineSmall) { slot() } }
                        text?.let { slot ->
                            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { slot() }
                                }
                            }
                        }
                        FlowRow(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberWindowFolds(rootOrigin: Offset): List<AdaptiveFold> {
    val layout = LocalOrbitWindowLayoutInfo.current
    val density = LocalDensity.current.density
    return layout?.displayFeatures.orEmpty().filterIsInstance<FoldingFeature>()
        .filter { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        .map { fold ->
            val bounds = fold.bounds
            AdaptiveFold(AdaptiveRegion(
                (bounds.left - rootOrigin.x) / density,
                (bounds.top - rootOrigin.y) / density,
                (bounds.right - rootOrigin.x) / density,
                (bounds.bottom - rootOrigin.y) / density,
            ), fold.orientation == FoldingFeature.Orientation.VERTICAL)
        }
}

/** Status wraps below the title instead of squeezing three columns on a Fold cover screen. */
@Composable
private fun AdaptiveHeader(
    title: String,
    status: String,
    statusColor: Color,
    orbitCount: Int,
    onAbout: () -> Unit,
    shortWindow: Boolean,
) {
    Surface(color = OrbitColors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (shortWindow) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (!shortWindow) Text(androidx.compose.ui.res.stringResource(R.string.app_name),
                        color = OrbitColors.cyan, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold)
                    Text(title, style = if (shortWindow) MaterialTheme.typography.titleLarge else
                        MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onAbout) { Text("About") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, color = statusColor, style = MaterialTheme.typography.labelSmall)
                Text("$orbitCount orbits", color = OrbitColors.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}
