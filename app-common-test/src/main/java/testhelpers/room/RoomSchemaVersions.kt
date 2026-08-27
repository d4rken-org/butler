package testhelpers.room

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val schemaJson = Json { ignoreUnknownKeys = true }

private fun exportedSchemaVersions(databaseClass: Class<*>): List<Int> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val versions = context.assets.list(databaseClass.canonicalName!!)
        ?.mapNotNull { it.removeSuffix(".json").toIntOrNull() }
    check(!versions.isNullOrEmpty()) { "No exported Room schemas found for ${databaseClass.canonicalName}" }
    return versions
}

/**
 * Highest exported schema version for [databaseClass], read from the module's `schemas/` dir
 * (exposed as test assets). Requires `exportSchema = true` and the schemas dir added via
 * `sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")`.
 */
fun latestRoomSchemaVersion(databaseClass: Class<*>): Int = exportedSchemaVersions(databaseClass).max()

/**
 * Every exported schema version of [databaseClass] mapped to its Room identity hash. Room stores that
 * hash inside the database and refuses to open one whose hash disagrees with the build's. CI fails on
 * an uncommitted schema JSON but cannot tell a NEW version from an EDITED one, so pinning this map in
 * a test is what catches a version that was regenerated in place.
 *
 * A HASH MISMATCH on an already-exported version calls for a version bump plus a migration, never a
 * new expected hash - the sole exception being a version that has genuinely never shipped. A load or
 * parse failure means the schema asset is missing or broken; restore the asset instead of bumping.
 */
fun roomSchemaIdentityHashes(databaseClass: Class<*>): Map<Int, String> {
    val assets = ApplicationProvider.getApplicationContext<Context>().assets
    return exportedSchemaVersions(databaseClass).associateWith { version ->
        val path = "${databaseClass.canonicalName}/$version.json"
        try {
            val raw = assets.open(path).bufferedReader().use { it.readText() }
            schemaJson.parseToJsonElement(raw)
                .jsonObject.getValue("database")
                .jsonObject.getValue("identityHash")
                .jsonPrimitive.content
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read the Room identity hash from $path", e)
        }
    }
}

/**
 * Validates that every exported schema version of [databaseClass] can migrate to the latest
 * version via [migrations]. Fails when a `@Database` version is bumped without a matching
 * [Migration] covering each older start version.
 */
fun MigrationTestHelper.validateAllMigrations(
    databaseClass: Class<*>,
    migrations: Array<Migration>,
    dbName: String = "migration-test",
) {
    val latest = latestRoomSchemaVersion(databaseClass)
    val startVersions = if (latest == 1) listOf(1) else (1 until latest).toList()
    startVersions.forEach { start ->
        createDatabase(dbName, start).close()
        runMigrationsAndValidate(dbName, latest, true, *migrations).close()
    }
}
