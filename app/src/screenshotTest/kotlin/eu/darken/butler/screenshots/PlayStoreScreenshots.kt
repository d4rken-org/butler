// For IDE design preview, open ScreenshotContent.kt instead.
package eu.darken.butler.screenshots

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

// Function names must stay free of underscores: copy_screenshots.sh splits the rendered file name
// at the first underscore to separate the function name from the locale.
//
// 23 shots, not a clean 8 x 3: the phone set leads with the multi-pane hero and has no explorer
// home shot, so it carries 7 while each tablet set carries 8. copy_screenshots.sh knows the
// per-form-factor counts and generate_screenshots.sh knows the total; all three have to agree.

// MARK: - Phone (7)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun MultiPanePhone() = MultiPaneContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun ExplorerDirectoryPhone() = ExplorerDirectoryContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun SearcherResultsPhone() = SearcherResultsContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun EditorViewPhone() = EditorViewContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun AppsManagerPhone() = AppsManagerContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun WorkspaceManagerPhone() = WorkspaceManagerContent(ScreenshotFormFactor.PHONE)

@PreviewTest
@PlayStoreLocalesPhone
@Composable
fun TemplatesPickerPhone() = TemplatesPickerContent(ScreenshotFormFactor.PHONE)

// MARK: - 7" tablet (8)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun ExplorerDirectorySeven() = ExplorerDirectoryContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun ExplorerHomeSeven() = ExplorerHomeContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun SearcherResultsSeven() = SearcherResultsContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun EditorViewSeven() = EditorViewContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun AppsManagerSeven() = AppsManagerContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun WorkspaceManagerSeven() = WorkspaceManagerContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun MultiPaneSeven() = MultiPaneContent(ScreenshotFormFactor.SEVEN)

@PreviewTest
@PlayStoreLocalesSeven
@Composable
fun TemplatesPickerSeven() = TemplatesPickerContent(ScreenshotFormFactor.SEVEN)

// MARK: - 10" tablet (8)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun ExplorerDirectoryTen() = ExplorerDirectoryContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun ExplorerHomeTen() = ExplorerHomeContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun SearcherResultsTen() = SearcherResultsContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun EditorViewTen() = EditorViewContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun AppsManagerTen() = AppsManagerContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun WorkspaceManagerTen() = WorkspaceManagerContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun MultiPaneTen() = MultiPaneContent(ScreenshotFormFactor.TEN)

@PreviewTest
@PlayStoreLocalesTen
@Composable
fun TemplatesPickerTen() = TemplatesPickerContent(ScreenshotFormFactor.TEN)
