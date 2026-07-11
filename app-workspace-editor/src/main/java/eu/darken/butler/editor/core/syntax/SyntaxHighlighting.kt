package eu.darken.butler.editor.core.syntax

/**
 * Language → tokenizer registry. A plain object rather than Hilt multibinding: the language set
 * is closed and single-module - adding a language is one tokenizer file plus one entry here.
 */
object SyntaxHighlighting {

    private val tokenizers: Map<Language, SyntaxTokenizer> = mapOf(
        Language.JAVASCRIPT to JsTokenizer(),
        Language.BASH to BashTokenizer(),
        Language.MARKDOWN to MarkdownTokenizer(),
        Language.JSON to JsonTokenizer(),
    )

    fun tokenizerFor(language: Language): SyntaxTokenizer = tokenizers.getValue(language)
}
