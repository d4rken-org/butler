package eu.darken.butler.common.debug.recorder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.theming.MyAppTheme
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.common.ui.Activity2
import eu.darken.butler.main.core.GeneralSettings
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class RecorderActivity : Activity2() {
    private val vm: RecorderViewModel by viewModels()

    @Inject lateinit var generalSettings: GeneralSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (intent.getStringExtra(RECORD_PATH) == null) {
            finish()
            return
        }

        setContent {
            val themeState by generalSettings.themeState.collectAsState(null)
            themeState?.let { theme ->
                MyAppTheme(state = theme) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ErrorEventHandler(vm)

                        LaunchedEffect(Unit) {
                            vm.closeEvent.onEach { finish() }.launchIn(this)
                        }

                        RecorderScreenHost(
                            viewModel = vm,
                        )
                    }
                }
            }
        }
    }

    companion object {
        internal val TAG = logTag("Debug", "Log", "RecorderActivity")
        const val RECORD_PATH = "logPath"

        fun getLaunchIntent(context: Context, path: String): Intent {
            val intent = Intent(context, RecorderActivity::class.java)
            intent.putExtra(RECORD_PATH, path)
            return intent
        }
    }
}
