package testhelpers.room

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider

/**
 * Highest exported schema version for [databaseClass], read from the module's `schemas/` dir
 * (exposed as test assets). Requires `exportSchema = true` and the schemas dir added via
 * `sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")`.
 */
fun latestRoomSchemaVersion(databaseClass: Class<*>): Int {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val versions = context.assets.list(databaseClass.canonicalName!!)
        ?.mapNotNull { it.removeSuffix(".json").toIntOrNull() }
    check(!versions.isNullOrEmpty()) { "No exported Room schemas found for ${databaseClass.canonicalName}" }
    return versions.max()
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
