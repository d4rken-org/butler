// For IDE design preview, open ScreenshotContent.kt instead.
package eu.darken.butler.screenshots

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@PlayStoreLocales
@Composable
fun ExplorerHome() = ExplorerHomeContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun ExplorerDirectory() = ExplorerDirectoryContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun SearcherResults() = SearcherResultsContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun EditorView() = EditorViewContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun AppsManager() = AppsManagerContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun WorkspaceManager() = WorkspaceManagerContent()
