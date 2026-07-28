// For IDE design preview, open ScreenshotContent.kt instead.
package eu.darken.butler.screenshots

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// Function names must stay free of underscores: copy_screenshots.sh splits the rendered file name
// at the first underscore to separate the function name from the locale.

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun ExplorerHomePhone() = ExplorerHomeContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun ExplorerHomeSeven() = ExplorerHomeContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun ExplorerHomeTen() = ExplorerHomeContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun ExplorerDirectoryPhone() = ExplorerDirectoryContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun ExplorerDirectorySeven() = ExplorerDirectoryContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun ExplorerDirectoryTen() = ExplorerDirectoryContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun SearcherResultsPhone() = SearcherResultsContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun SearcherResultsSeven() = SearcherResultsContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun SearcherResultsTen() = SearcherResultsContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun EditorViewPhone() = EditorViewContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun EditorViewSeven() = EditorViewContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun EditorViewTen() = EditorViewContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun AppsManagerPhone() = AppsManagerContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun AppsManagerSeven() = AppsManagerContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun AppsManagerTen() = AppsManagerContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun WorkspaceManagerPhone() = WorkspaceManagerContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun WorkspaceManagerSeven() = WorkspaceManagerContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun WorkspaceManagerTen() = WorkspaceManagerContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun MultiPanePhone() = MultiPaneContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun MultiPaneSeven() = MultiPaneContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun MultiPaneTen() = MultiPaneContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun TemplatesPickerPhone() = TemplatesPickerContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun TemplatesPickerSeven() = TemplatesPickerContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun TemplatesPickerTen() = TemplatesPickerContent(ScreenshotFormFactor.TEN)
