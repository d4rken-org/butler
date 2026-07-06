package eu.darken.butler.editor.core.engine

/**
 * A replace operation's target no longer matches the document: the text at the recorded offset
 * changed between search and replace. Nothing was modified - the caller re-searches and retries.
 */
class StaleMatchException : Exception("The match is stale, the document changed since the search")
