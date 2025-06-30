package eu.darken.butler.explorer.core.engine

import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Code
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ExplorerEngine @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
) {

    suspend fun getHomeEntry(): ExplorerLocation = withContext(Dispatchers.IO) {
        ExplorerLocation.Home(
            items = listOf(
                ExplorerLocation.Home.Item(
                    icon = Icons.TwoTone.PhoneAndroid,
                    label = caString { "Device" },
                    target = getDevice()
                ),
            )
        )
    }

    suspend fun getDevice(): ExplorerLocation = withContext(Dispatchers.IO) {
        ExplorerLocation.Device(
            items = listOf(
                ExplorerLocation.Device.Item(
                    icon = Icons.TwoTone.Code,
                    label = caString { "Root" },
                    target = LocalPath.Companion.build(Environment.getRootDirectory()),
                ),
                ExplorerLocation.Device.Item(
                    icon = Icons.TwoTone.Storage,
                    label = caString { "Internal public storage" },
                    target = LocalPath.Companion.build(Environment.getExternalStorageDirectory()),
                ),
            )
        )
    }

    suspend fun getContent(path: APath): List<ExplorerPathItem> = withContext(Dispatchers.IO) {
        // First stage: Load basic file info quickly
        val basicLookups = gatewaySwitch.lookupFiles(path)
        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with basic info
        basicLookups.map { lookup ->
            fileClassifier.classify(lookup)
        }
    }

    suspend fun getContentExtended(path: APath): List<ExplorerPathItem> = withContext(Dispatchers.IO) {
        // Second stage: Load extended info with permissions/ownership
        val extendedLookups = gatewaySwitch.lookupFilesExtended(path)
        val fileClassifier = FileTypeClassifier()

        // Convert to ExplorerPathItem with extended info
        extendedLookups.map { extendedLookup ->
            val basicItem = fileClassifier.classify(extendedLookup)
            basicItem.withExtendedData(
                ownership = extendedLookup.ownership,
                permissions = extendedLookup.permissions
            )
        }
    }


}