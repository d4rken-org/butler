package eu.darken.butler.common.error

import android.app.Activity
import android.util.TypedValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import eu.darken.butler.common.R

fun Throwable.asErrorDialogBuilder(
    context: Activity
): MaterialAlertDialogBuilder {
    return MaterialAlertDialogBuilder(context).apply {
        val error = this@asErrorDialogBuilder
        val localizedError = error.localized(context)

        setTitle(localizedError.label.get(context))

        val messageView = MaterialTextView(context).apply {
            text = localizedError.description.get(context)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextIsSelectable(true)

            val paddingHorizontal = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
            ).toInt()
            val paddingVertical = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
            ).toInt()
            setPadding(
                paddingHorizontal,
                paddingVertical,
                paddingHorizontal,
                0,
            )
        }
        setView(messageView)

        if (localizedError.fixAction != null) {
            setPositiveButton(
                localizedError.fixActionLabel?.get(context) ?: context.getString(android.R.string.ok)
            ) { _, _ ->
                localizedError.fixAction!!.invoke(context)
            }
            setNegativeButton(R.string.general_cancel_action) { _, _ ->
            }
        } else {
            setPositiveButton(android.R.string.ok) { _, _ ->
            }
        }

        when {
            localizedError.infoAction != null -> {
                setNeutralButton(
                    localizedError.infoActionLabel?.get(context)
                        ?: context.getString(R.string.general_show_details_action)
                ) { _, _ ->
                    localizedError.infoAction!!.invoke(context)
                }
            }
        }
    }
}