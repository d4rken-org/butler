package eu.darken.butler.editor.ui.editor

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.TextPosition

@Composable
internal fun TextLineItem(
    lineIndex: Int,
    lineContent: String,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    isCurrentLine: Boolean,
    isFocused: Boolean,
    wordWrap: Boolean,
    fontSize: Int,
    tabSize: Int,
    modifier: Modifier = Modifier
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val cursorColor = MaterialTheme.colorScheme.primary
    val expandedText = remember(lineContent, tabSize) { lineContent.expandTabs(tabSize) }

    // Blinking animation when focused
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isFocused) 0f else 1f,
        animationSpec = if (isFocused) {
            infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1060
                    1f at 0
                    1f at 530
                    0f at 531
                    0f at 1060
                },
                repeatMode = RepeatMode.Restart
            )
        } else {
            infiniteRepeatable(
                animation = tween(1),
                repeatMode = RepeatMode.Restart
            )
        },
        label = "cursor_alpha"
    )

    val backgroundColor = if (isCurrentLine) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    val cursorModifier = if (isCurrentLine && selection == null) {
        Modifier.drawWithContent {
            drawContent()
            val expandedText = lineContent.expandTabs(tabSize)
            val position = cursorPosition.column

            val layoutResult = textLayoutResult
            val cursorX = when {
                layoutResult != null && position < expandedText.length -> {
                    val boundingBox = layoutResult.getBoundingBox(position)
                    boundingBox.left
                }
                layoutResult != null && position == expandedText.length && expandedText.isNotEmpty() -> {
                    val boundingBox = layoutResult.getBoundingBox(expandedText.length - 1)
                    boundingBox.right
                }
                else -> {
                    val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                    position * charWidth
                }
            }

            if (isFocused) {
                drawLine(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    start = Offset(cursorX, 0f),
                    end = Offset(cursorX, size.height),
                    strokeWidth = 3.dp.toPx()
                )
            } else {
                val layoutResultForWidth = textLayoutResult
                val charWidth = when {
                    layoutResultForWidth != null && position < expandedText.length - 1 -> {
                        val currentBox = layoutResultForWidth.getBoundingBox(position)
                        val nextBox = layoutResultForWidth.getBoundingBox(position + 1)
                        (nextBox.left - currentBox.left).coerceAtLeast(0f)
                    }
                    layoutResultForWidth != null && position == expandedText.length - 1 && expandedText.isNotEmpty() -> {
                        val box = layoutResultForWidth.getBoundingBox(position)
                        if (position > 0) {
                            val prevBox = layoutResultForWidth.getBoundingBox(position - 1)
                            (box.right - box.left).coerceAtLeast(0f)
                        } else {
                            with(density) { (fontSize * 0.6f).sp.toPx() }
                        }
                    }
                    else -> {
                        with(density) { (fontSize * 0.6f).sp.toPx() }
                    }
                }

                val blockWidth = if (position < expandedText.length) {
                    charWidth
                } else {
                    charWidth * 0.3f
                }

                drawRect(
                    color = cursorColor.copy(alpha = 0.4f),
                    topLeft = Offset(cursorX, 0f),
                    size = Size(blockWidth, size.height)
                )
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(cursorModifier)
    ) {
        SelectableText(
            text = lineContent.expandTabs(tabSize).ifEmpty { " " },
            lineIndex = lineIndex,
            cursorPosition = cursorPosition,
            selection = selection,
            wordWrap = wordWrap,
            fontSize = fontSize,
            onTextLayout = { layoutResult ->
                textLayoutResult = layoutResult
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SelectableText(
    text: String,
    lineIndex: Int,
    cursorPosition: TextPosition,
    selection: Pair<TextPosition, TextPosition>?,
    wordWrap: Boolean,
    fontSize: Int,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = MaterialTheme.colorScheme.onSurface
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
            modifier = modifier
        ) {
            selection?.let { (start, end) ->
                if (lineIndex >= start.line && lineIndex <= end.line) {
                    val selectionStart = if (lineIndex == start.line) start.column else 0
                    val selectionEnd = if (lineIndex == end.line) end.column else text.length

                    if (selectionStart < selectionEnd) {
                        // Use TextLayoutResult for accurate positioning if available
                        val layout = layoutResult
                        val (startX, width) = if (layout != null && selectionStart < text.length && selectionEnd <= text.length) {
                            try {
                                val startBounds = if (selectionStart < text.length) {
                                    layout.getBoundingBox(selectionStart)
                                } else {
                                    layout.getBoundingBox(text.length - 1).let { box ->
                                        box.copy(left = box.right)
                                    }
                                }

                                val endBounds = if (selectionEnd > 0 && selectionEnd <= text.length) {
                                    layout.getBoundingBox((selectionEnd - 1).coerceAtLeast(0))
                                } else {
                                    startBounds
                                }

                                val startXPos = startBounds.left
                                val endXPos = endBounds.right
                                startXPos to (endXPos - startXPos)
                            } catch (e: Exception) {
                                // Fallback to character width estimation
                                val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                                (selectionStart * charWidth) to ((selectionEnd - selectionStart) * charWidth)
                            }
                        } else {
                            // Fallback to character width estimation
                            val charWidth = with(density) { (fontSize * 0.6f).sp.toPx() }
                            (selectionStart * charWidth) to ((selectionEnd - selectionStart) * charWidth)
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { startX.toDp() })
                                .width(with(density) { width.toDp() })
                                .height(with(density) {
                                    val height: Float = layoutResult?.size?.height?.toFloat() ?: (fontSize * 1.5f).sp.toPx()
                                    height.toDp()
                                })
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            Text(
                text = if (text.isEmpty()) " " else text,
                style = TextStyle(
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor
                ),
                softWrap = wordWrap,
                overflow = TextOverflow.Visible,
                onTextLayout = { result ->
                    layoutResult = result
                    onTextLayout(result)
                },
                modifier = if (wordWrap) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.wrapContentWidth()
                }
            )
    }
}

@Preview2
@Composable
private fun TextLineItemPreview() {
    PreviewWrapper {
        TextLineItem(
            lineIndex = 0,
            lineContent = "fun calculateSum(a: Int, b: Int): Int {",
            cursorPosition = TextPosition(offset = 15, line = 0, column = 15),
            selection = null,
            isCurrentLine = true,
            isFocused = true,
            wordWrap = false,
            fontSize = 14,
            tabSize = 4,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview2
@Composable
private fun SelectableTextPreview() {
    PreviewWrapper {
        SelectableText(
            text = "fun calculateSum(a: Int, b: Int): Int {",
            lineIndex = 0,
            cursorPosition = TextPosition(offset = 15, line = 0, column = 15),
            selection = TextPosition(offset = 4, line = 0, column = 4) to TextPosition(offset = 16, line = 0, column = 16),
            wordWrap = false,
            fontSize = 14,
            onTextLayout = {},
            modifier = Modifier.fillMaxWidth()
        )
    }
}
