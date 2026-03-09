package eu.darken.butler.common.debug.recorder.ui.result

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
import eu.darken.butler.common.theming.ButlerTheme
import eu.darken.butler.common.ui.Activity2
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeState
import javax.inject.Inject

@AndroidEntryPoint
class RecorderActivity : Activity2() {
    private val vm: RecorderViewModel by viewModels()

    @Inject lateinit var generalSettings: GeneralSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val hasSession = intent.getStringExtra(RECORD_SESSION_ID) != null
        val hasPath = intent.getStringExtra(RECORD_PATH) != null
        if (!hasSession && !hasPath) {
            finish()
            return
        }

        setContent {
            val themeState by generalSettings.themeState.collectAsState(null)
            themeState?.let { theme ->
                ButlerTheme(state = theme) {
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        ErrorEventHandler(vm)

                        LaunchedEffect(Unit) {
                            vm.events.collect { event ->
                                when (event) {
                                    is RecorderViewModel.Event.ShareIntent -> startActivity(event.intent)
                                    is RecorderViewModel.Event.Finish -> finish()
                                }
                            }
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
        const val RECORD_SESSION_ID = "sessionId"
        const val RECORD_PATH = "logPath"

        fun getLaunchIntent(context: Context, sessionId: String, legacyPath: String? = null): Intent {
            return Intent(context, RecorderActivity::class.java).apply {
                putExtra(RECORD_SESSION_ID, sessionId)
                if (legacyPath != null) putExtra(RECORD_PATH, legacyPath)
            }
        }
    }
}
