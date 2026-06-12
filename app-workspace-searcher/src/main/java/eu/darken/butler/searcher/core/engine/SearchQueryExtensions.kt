package eu.darken.butler.searcher.core.engine

import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery

val FilenameQuery.patternOptions: PatternOptions
    get() = PatternOptions(
        caseSensitive = caseSensitive,
        useRegex = useRegex,
        wholeWord = wholeWord,
    )

val ContentQuery.patternOptions: PatternOptions
    get() = PatternOptions(
        caseSensitive = caseSensitive,
        useRegex = useRegex,
        wholeWord = wholeWord,
    )
