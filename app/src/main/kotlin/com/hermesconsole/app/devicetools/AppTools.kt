package com.hermesconsole.app.devicetools

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.hermesconsole.app.DrawerApp
import com.hermesconsole.app.launchableApps
import com.hermesconsole.hermesclient.DeviceTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "DeviceTools"

/**
 * The tools the console runs for the model on the tablet itself: list_apps and launch_app, both
 * over the drawer's own list ([launchableApps] of the [resolved] launcher query), so the model sees
 * exactly the apps a hand on the wall can tap.
 *
 * launch_app launches only on one clear match ([matchApp]). Several matches or none go back to the
 * model as text with nothing launched: it can ask which one was meant, or call again with a label
 * from the list.
 */
fun appTools(
    resolved: () -> List<DrawerApp>,
    selfPackage: String,
    launch: suspend (DrawerApp) -> Unit,
): List<DeviceTool> {
    fun apps() = launchableApps(resolved(), selfPackage)

    val listApps = DeviceTool(
        name = "list_apps",
        description = "List the apps installed on the console's tablet, with each app's label and package.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    ) {
        buildJsonObject {
            putJsonArray("apps") {
                apps().forEach { app ->
                    add(buildJsonObject {
                        put("label", app.label)
                        put("package", app.packageName)
                    })
                }
            }
        }.toString()
    }

    val launchApp = DeviceTool(
        name = "launch_app",
        description = "Open an app on the console's tablet by its name. Opens it when one app matches; " +
            "when several or none match, nothing opens and the reply lists the apps to choose from.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The app's name as the user said it, for example 'calculator'.")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("name")) })
        },
    ) { args ->
        val name = args.stringArg("name")
        val apps = apps()
        val reply = when (val match = matchApp(name, apps)) {
            is AppMatch.One -> {
                launch(match.app)
                buildJsonObject { put("launched", match.app.label) }
            }

            is AppMatch.Several -> buildJsonObject {
                putJsonArray("candidates") { match.apps.forEach { add(JsonPrimitive(it.label)) } }
                put("note", "Several apps match '$name'. Nothing was opened. Ask which one, or call again with the exact label.")
            }

            AppMatch.None -> buildJsonObject {
                putJsonArray("apps") { apps.forEach { add(JsonPrimitive(it.label)) } }
                put("note", "No app matches '$name'. Nothing was opened. These are the installed apps.")
            }
        }
        Log.i(TAG, "launch_app '$name' -> $reply")
        reply.toString()
    }

    return listOf(listApps, launchApp)
}

/** What a spoken app name picks out of the drawer's list. */
sealed interface AppMatch {
    data class One(val app: DrawerApp) : AppMatch
    data class Several(val apps: List<DrawerApp>) : AppMatch
    data object None : AppMatch
}

/**
 * Match [name] against the app labels, ignoring case. An exact label wins - "youtube" is YouTube
 * even with YouTube Music installed. Otherwise every label that contains the name is a match.
 */
fun matchApp(name: String, apps: List<DrawerApp>): AppMatch {
    val wanted = name.trim().lowercase()
    if (wanted.isEmpty()) return AppMatch.None
    val exact = apps.filter { it.label.lowercase() == wanted }
    val matches = exact.ifEmpty { apps.filter { wanted in it.label.lowercase() } }
    return when (matches.size) {
        0 -> AppMatch.None
        1 -> AppMatch.One(matches.single())
        else -> AppMatch.Several(matches)
    }
}

/**
 * The raw launcher query the drawer runs, without its icons: a tool call needs labels, and
 * rasterising every icon on each call would only slow the reply.
 */
fun PackageManager.launcherApps(): List<DrawerApp> =
    queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { info ->
        DrawerApp(
            label = info.loadLabel(this).toString(),
            packageName = info.activityInfo.packageName,
            activityName = info.activityInfo.name,
        )
    }

private fun JsonObject.stringArg(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()
