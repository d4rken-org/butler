package eu.darken.butler.searcher.core.engine

import eu.darken.butler.searcher.core.ContentQuery
import eu.darken.butler.searcher.core.FilenameQuery

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
