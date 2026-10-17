package com.hermesconsole.lcars

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * The preview harness, on device (REQ-19): the whole kit rendered with the console not running -
 * no mic, no sockets, no backends. Debug-only, so it never ships in a release build.
 *
 *     adb shell am start -n com.hermesconsole/com.hermesconsole.lcars.LcarsGalleryActivity
 */
class LcarsGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LcarsGallery(Modifier.fillMaxSize()) }
    }
}
