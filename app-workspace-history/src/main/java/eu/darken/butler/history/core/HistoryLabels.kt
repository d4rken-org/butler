package eu.darken.butler.history.core

import androidx.annotation.StringRes
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome

/*
 * Resource ids rather than resolved strings: the share text is built in the ViewModel with a
 * Context, where a @Composable `stringResource` can't be called, while the UI resolves the same ids
 * through `stringResource`.
 */

@get:StringRes
internal val HistoryOutcome.labelRes: Int
    get() = when (this) {
        HistoryOutcome.COMPLETED -> R.string.history_filter_outcome_completed
        HistoryOutcome.PARTIAL -> R.string.history_filter_outcome_partial
        HistoryOutcome.FAILED -> R.string.history_filter_outcome_failed
        HistoryOutcome.CANCELLED -> R.string.history_filter_outcome_cancelled
    }

@get:StringRes
internal val Operation.Metadata.Kind.labelRes: Int
    get() = when (this) {
        Operation.Metadata.Kind.COPY -> R.string.history_filter_kind_copy
        Operation.Metadata.Kind.MOVE -> R.string.history_filter_kind_move
        Operation.Metadata.Kind.DELETE -> R.string.history_filter_kind_delete
        Operation.Metadata.Kind.CREATE_FILE -> R.string.history_filter_kind_create_file
        Operation.Metadata.Kind.CREATE_FOLDER -> R.string.history_filter_kind_create_folder
        Operation.Metadata.Kind.SAVE -> R.string.history_filter_kind_save
        Operation.Metadata.Kind.COMPRESS -> R.string.history_filter_kind_compress
        Operation.Metadata.Kind.EXTRACT -> R.string.history_filter_kind_extract
        Operation.Metadata.Kind.RESTORE -> R.string.history_filter_kind_restore
        Operation.Metadata.Kind.INSTALL -> R.string.history_filter_kind_install
    }

@get:StringRes
internal val HistoryEntry.OriginType.labelRes: Int
    get() = when (this) {
        HistoryEntry.OriginType.EXPLORER -> R.string.history_origin_explorer
        HistoryEntry.OriginType.SEARCHER -> R.string.history_origin_searcher
        HistoryEntry.OriginType.SAVER -> R.string.history_origin_saver
        HistoryEntry.OriginType.DEVELOPER -> R.string.history_origin_developer
        HistoryEntry.OriginType.VIEWER -> R.string.history_origin_viewer
    }

/** Past tense, unlike [labelRes]: the row headline reads as what happened, not as a filter value. */
@get:StringRes
internal val Operation.Metadata.Kind.headlineLabelRes: Int
    get() = when (this) {
        Operation.Metadata.Kind.COPY -> R.string.history_entry_kind_copy
        Operation.Metadata.Kind.MOVE -> R.string.history_entry_kind_move
        Operation.Metadata.Kind.DELETE -> R.string.history_entry_kind_delete
        Operation.Metadata.Kind.CREATE_FILE -> R.string.history_entry_kind_create_file
        Operation.Metadata.Kind.CREATE_FOLDER -> R.string.history_entry_kind_create_folder
        Operation.Metadata.Kind.SAVE -> R.string.history_entry_kind_save
        Operation.Metadata.Kind.COMPRESS -> R.string.history_entry_kind_compress
        Operation.Metadata.Kind.EXTRACT -> R.string.history_entry_kind_extract
        Operation.Metadata.Kind.RESTORE -> R.string.history_entry_kind_restore
        Operation.Metadata.Kind.INSTALL -> R.string.history_entry_kind_install
    }

@get:StringRes
internal val Operation.Metadata.Intent.headlineLabelRes: Int
    get() = when (this) {
        Operation.Metadata.Intent.RENAME -> R.string.history_entry_intent_rename
        Operation.Metadata.Intent.PASTE_COPY -> R.string.history_entry_intent_paste_copy
        Operation.Metadata.Intent.PASTE_MOVE -> R.string.history_entry_intent_paste_move
        Operation.Metadata.Intent.DROP_COPY -> R.string.history_entry_intent_drop_copy
        Operation.Metadata.Intent.DROP_MOVE -> R.string.history_entry_intent_drop_move
    }
