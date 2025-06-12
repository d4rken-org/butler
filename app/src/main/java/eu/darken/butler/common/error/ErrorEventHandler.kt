package eu.darken.butler.common.error

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

@Composable
fun ErrorEventHandler(source: ErrorEventSource) {
    val context = LocalContext.current as Activity
    val errorEvents = source.errorEvents
    LaunchedEffect(errorEvents) {
        errorEvents.collect { error -> error.asErrorDialogBuilder(context).show() }
    }
}