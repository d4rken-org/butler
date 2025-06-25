package eu.darken.butler.common.ui

import kotlinx.coroutines.flow.StateFlow

interface  ScreenStateSource<T> {

    val state: StateFlow<T>
}