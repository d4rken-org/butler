package eu.darken.butler.common.adb

import kotlinx.coroutines.flow.first

suspend fun AdbManager.canUseAdbNow(): Boolean = useAdb.first()