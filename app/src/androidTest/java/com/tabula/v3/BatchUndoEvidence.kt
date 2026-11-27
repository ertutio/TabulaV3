package com.tabula.v3

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock

/** Screenshots are optional and only written when the test runner requests evidence. */
internal fun captureBatchUndoEvidence(instrumentation: Instrumentation, context: Context, name: String) {
    if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("captureBatchUndo") != "true"
    ) return

    // The snackbar enters with a slide and fade; capture after the entrance settles.
    SystemClock.sleep(250)
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        val directory = requireNotNull(context.getExternalFilesDir(null)).resolve("batch-undo-evidence")
        check(directory.isDirectory || directory.mkdirs())
        directory.resolve("$name.png").outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    } finally {
        bitmap.recycle()
    }
}
