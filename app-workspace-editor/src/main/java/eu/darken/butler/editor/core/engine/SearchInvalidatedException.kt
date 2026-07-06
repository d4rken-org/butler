package eu.darken.butler.editor.core.engine

/**
 * The document was structurally modified while a search was scanning it. The scan's results
 * would be positionally stale, so it aborts instead of blocking edits for its whole duration;
 * callers simply drop the result (an edit clears search state anyway).
 */
class SearchInvalidatedException : Exception("Document changed during search")
