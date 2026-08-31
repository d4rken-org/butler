package eu.darken.butler.common.files.errors

import eu.darken.butler.common.error.HasLocalizedError

/**
 * Marks a failure as "the target is gone", i.e. the operation failed because the thing itself no
 * longer exists rather than because something went wrong reaching it.
 *
 * A marker rather than a base class: the implementors deliberately disagree on details a shared
 * supertype would have to settle. [PathNotFoundException] is cause-less so the classifier cannot
 * re-find the failure it replaced, while a workspace-local variant may want to keep the original
 * error, and not every one of them has an [eu.darken.butler.common.files.APath] to point at.
 *
 * Renderers use this to pick the "it's gone" presentation without knowing any concrete type. The
 * [HasLocalizedError] requirement is what makes that safe: the shared screen shows nothing but the
 * implementor's own wording, and the generic fallback it would otherwise land on puts the exception
 * class name in the title - the exact thing this presentation exists to avoid.
 */
interface PathGoneError : HasLocalizedError
