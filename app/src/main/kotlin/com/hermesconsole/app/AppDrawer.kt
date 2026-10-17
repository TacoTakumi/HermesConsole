package com.hermesconsole.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.hermesconsole.lcars.LcarsBar
import com.hermesconsole.lcars.LcarsColors
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsMetrics
import com.hermesconsole.lcars.LcarsPill
import com.hermesconsole.lcars.LcarsSegment
import com.hermesconsole.lcars.LcarsTheme
import com.hermesconsole.lcars.LocalLcarsCues

/**
 * One launchable app as the drawer knows it (T-53, REQ-42): its label to show, and the exact
 * component to launch. Pure data - no [android.graphics.drawable.Drawable], no PackageManager -
 * so the drawer's whole selection logic ([launchableApps]) is provable on a plain JVM.
 */
data class DrawerApp(
    val label: String,
    val packageName: String,
    val activityName: String,
)

/**
 * The drawer's display list from the raw MAIN/CATEGORY_LAUNCHER query (REQ-42). Three rules, all
 * pinned by [AppDrawerTest]:
 *  - the console itself is dropped: it is HOME, so it resolves the query like any app, and
 *    launching it from its own drawer is a confusing no-op;
 *  - an entry with no label to show is dropped: it has nothing to render and nothing to read
 *    before a tap, so it is a ghost, not an app;
 *  - the rest are ordered by label, case-insensitively, so the wall reads like a phone book.
 *
 * Activities, not packages: a suite that publishes two launcher entries keeps both, each launchable
 * on its own component - the requirement is "every activity resolving MAIN/CATEGORY_LAUNCHER".
 */
fun launchableApps(resolved: List<DrawerApp>, selfPackage: String): List<DrawerApp> =
    resolved
        .filter { it.packageName != selfPackage && it.label.isNotBlank() }
        .sortedBy { it.label.lowercase() }

/** One drawer app ready for the wall: the pure entry plus its icon, where one loads. */
class DrawerEntry(val app: DrawerApp, val icon: ImageBitmap?)

private val DrawerApp.component get() = "$packageName/$activityName"

/**
 * The device's launchable apps as the drawer shows them, queried live (REQ-42): every activity
 * resolving MAIN/CATEGORY_LAUNCHER, run through [launchableApps] to drop the console and order the
 * rest, each carrying its icon where one loads. Re-run on every open (the screen calls this as it
 * composes), so an install or a removal appears without a console restart.
 *
 * Package visibility on API 30+ is granted by the `<queries>` MAIN/LAUNCHER block in the manifest -
 * a launcher legitimately needs to see what it launches, declared narrowly rather than by
 * QUERY_ALL_PACKAGES.
 */
fun PackageManager.launcherEntries(selfPackage: String): List<DrawerEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val icons = HashMap<String, ImageBitmap?>()
    val resolved = queryIntentActivities(intent, 0).map { info ->
        val app = DrawerApp(
            label = info.loadLabel(this).toString(),
            packageName = info.activityInfo.packageName,
            activityName = info.activityInfo.name,
        )
        // An adaptive or oddly-sized icon can throw on rasterisation; an icon is decoration, so a
        // failed load costs a label its picture, never the drawer its entry.
        icons[app.component] = runCatching { info.loadIcon(this).toBitmap().asImageBitmap() }.getOrNull()
        app
    }
    return launchableApps(resolved, selfPackage).map { DrawerEntry(it, icons[it.component]) }
}

/** The launch intent for a drawer entry: its exact component, in its own task. */
fun DrawerApp.launchIntent(): Intent =
    Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(packageName, activityName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

/**
 * The app drawer (T-53, REQ-42): the console's own launcher pane. The console is the tablet's HOME
 * app, so there is no system drawer to borrow - this is the tablet's only touch path to its other
 * apps. A grid of the device's launchable apps, each a tile that fires its [onLaunch]; CLOSE (and
 * the device back) dismiss it via [onClose].
 *
 * The voice loop is untouched by the visit: it rides [MicForegroundService], and every tile - like
 * every kit control - chirps [LcarsCue.CONFIRM] through the shell's cue provider (REQ-39), the
 * touch confirmation T-54 audits every pressable for.
 */
@Composable
fun AppDrawerScreen(
    entries: List<DrawerEntry>,
    onLaunch: (DrawerApp) -> Unit,
    onClose: () -> Unit,
) {
    // Device back dismisses the pane in place (REQ-42), exactly as CLOSE does - without it the
    // console is HOME (the task root), so the system's default back recreates the whole activity,
    // tearing down and rebuilding the live console just to shut a launcher. Back closes the drawer;
    // it does not restart the console.
    BackHandler { onClose() }
    LcarsTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LcarsColors.Space)
                .padding(LcarsMetrics.Gap * 2),
            verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
        ) {
            LcarsBar(
                segments = listOf(
                    LcarsSegment("apps", LcarsColors.AfricanViolet, weight = 0.25f),
                    LcarsSegment(null, LcarsColors.Bluey, weight = 0.5f),
                    LcarsSegment(
                        // A live count doubles as proof the query is fresh: a just-installed app
                        // bumps it the next time the drawer opens.
                        if (entries.isEmpty()) "no apps" else "${entries.size} apps",
                        LcarsColors.IceBlue,
                        weight = 0.25f,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
                capStart = true,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
            ) {
                if (entries.isEmpty()) {
                    BasicText(
                        text = "NOTHING TO LAUNCH",
                        style = LcarsTheme.typography.readout.copy(color = LcarsColors.Gray),
                    )
                } else {
                    // A grid, chunked by hand rather than pulled in as a dependency: the landscape
                    // frame (REQ-38) is wide, so three tiles a row read better than one long column.
                    entries.chunked(COLUMNS).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 2),
                        ) {
                            row.forEachIndexed { column, entry ->
                                AppTile(
                                    entry = entry,
                                    // The rail's own run of colours, cycled across the grid so it
                                    // reads as LCARS chrome rather than a flat table.
                                    accent = LcarsColors.RailRun[column % LcarsColors.RailRun.size],
                                    modifier = Modifier.weight(1f),
                                    onClick = { onLaunch(entry.app) },
                                )
                            }
                            // Pad the last row so its tiles keep the grid's width, not stretch to fill.
                            repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                LcarsPill(text = "close", color = LcarsColors.Bluey, onClick = onClose)
            }
        }
    }
}

/**
 * One app in the grid: its icon (where one loaded) and label on a dark tile framed in an accent -
 * colourful launcher icons read against black far better than punched out of a bright LCARS fill,
 * and the accent border keeps it kit chrome. Chirps its touch like every kit control (REQ-39).
 */
@Composable
private fun AppTile(
    entry: DrawerEntry,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cues = LocalLcarsCues.current
    val shape = RoundedCornerShape(LcarsMetrics.InnerRadius)
    Row(
        modifier = modifier
            .height(TileHeight)
            .clip(shape)
            .background(LcarsColors.Space)
            .border(width = 2.dp, color = accent, shape = shape)
            .clickable {
                cues(LcarsCue.CONFIRM)
                onClick()
            }
            .padding(horizontal = LcarsMetrics.LabelPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LcarsMetrics.Gap * 3),
    ) {
        entry.icon?.let { icon ->
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                contentScale = ContentScale.Fit,
            )
        }
        BasicText(
            text = entry.app.label,
            modifier = Modifier.weight(1f),
            style = LcarsTheme.typography.label.copy(color = accent),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Three tiles across the landscape frame; a tile tall enough for a two-line label beside its icon. */
private const val COLUMNS = 3
private val TileHeight = 72.dp
private val IconSize = 40.dp
