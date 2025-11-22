# Room Schema Change Detection Tests

## Overview

This document outlines the plan to implement automatic schema change detection for Room databases in the Butler app. This ensures that database schema changes cannot happen without being noticed and properly versioned.

## Current State

Butler has **3 Room databases** (all at version 1):

1. **RecycleBinDatabase** (app-common-io) - currently has uncommitted breaking changes
2. **SearchHistoryDatabase** (app-workspace-searcher)
3. **SAFLocationDatabase** (app-common-io)

**Issue**: While all databases have `exportSchema = true` in their
`@Database` annotations, the gradle configuration to export schemas is missing from the feature modules. No schema JSON files are being generated.

## Solution: Schema Export + Validation Tests

Room provides built-in support for schema change detection through schema export files:

1. **Schema Export**: Room generates JSON files representing the database schema
2. **Version Control**: These files are committed to git
3. **Automatic Detection**: Any schema change without a version bump will modify these files
4. **Test Validation**: Unit tests validate that schema files match expected versions

## Implementation Plan

### 1. Configure Schema Export in Gradle

Add schema export configuration to the following gradle files:

**app-common-io/build.gradle.kts:**

```kotlin
android {
    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }
    }
}
```

**app-workspace-searcher/build.gradle.kts:**

```kotlin
android {
    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }
    }
}
```

### 2. Generate Initial Schema Files

Build the project to generate schema JSON files:

```bash
./gradlew :app-common-io:compileDebugKotlin --no-daemon
./gradlew :app-workspace-searcher:compileDebugKotlin --no-daemon
```

Schema files will be created at:

- `app-common-io/schemas/eu.darken.butler.common.recyclebin.db.RecycleBinDatabase/1.json`
- `app-common-io/schemas/eu.darken.butler.common.files.saf.location.db.SAFLocationDatabase/1.json`
- `app-workspace-searcher/schemas/eu.darken.butler.searcher.core.history.db.SearchHistoryDatabase/1.json`

### 3. Create Schema Validation Tests

For each database, create unit tests that:

#### Example: RecycleBinDatabaseSchemaTest.kt

```kotlin
package eu.darken.butler.common.recyclebin.db

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

class RecycleBinDatabaseSchemaTest {

    @Test
    fun `schema version matches database annotation`() {
        val schemaFile = File("schemas/eu.darken.butler.common.recyclebin.db.RecycleBinDatabase/1.json")
        schemaFile.exists() shouldBe true

        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val version = schema["version"]?.jsonPrimitive?.int

        version shouldBe 1 // Must match @Database(version = 1)
    }

    @Test
    fun `schema file exists for current version`() {
        val expectedVersion = 1 // Update when version changes
        val schemaFile = File("schemas/eu.darken.butler.common.recyclebin.db.RecycleBinDatabase/$expectedVersion.json")

        schemaFile.exists() shouldBe true
    }

    @Test
    fun `recycle_bin_items table has expected structure`() {
        val schemaFile = File("schemas/eu.darken.butler.common.recyclebin.db.RecycleBinDatabase/1.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject

        // Validate table exists and has expected columns
        // This test will fail if schema changes without version bump
    }
}
```

Create similar tests for:

- `SearchHistoryDatabaseSchemaTest.kt` in app-workspace-searcher
- `SAFLocationDatabaseSchemaTest.kt` in app-common-io

### 4. Commit Schema Files to Git

```bash
git add app-common-io/schemas/
git add app-workspace-searcher/schemas/
git commit -m "Add Room schema export files for version tracking"
```

**Benefits:**

- Any schema change will show as a git diff
- Code reviews can catch unintended schema modifications
- CI/CD can enforce version bumps for schema changes

### 5. (Optional) Add Migration Tests

For more robust testing, add migration validation:

**Add dependency:**

```kotlin
// In module build.gradle.kts
dependencies {
    androidTestImplementation("androidx.room:room-testing:2.7.0")
}
```

**Create migration test:**

```kotlin
@RunWith(AndroidJUnit4::class)
class RecycleBinDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RecycleBinDatabase::class.java
    )

    @Test
    fun migrate1to2() {
        // Create database at version 1
        val db = helper.createDatabase(TEST_DB, 1)
        // Insert test data
        db.close()

        // Migrate to version 2
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // Validate data integrity after migration
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
```

## Workflow for Schema Changes

### When Making Schema Changes:

1. **Modify Entity or Database**
    - Update `@Entity` classes or `@Database` configuration

2. **Bump Database Version**
   ```kotlin
   @Database(
       entities = [RecycleBinEntity::class],
       version = 2, // Increment version
       exportSchema = true
   )
   ```

3. **Implement Migration**
   ```kotlin
   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // SQL migration statements
       }
   }
   ```

4. **Rebuild to Generate New Schema**
   ```bash
   ./gradlew :app-common-io:compileDebugKotlin --no-daemon
   ```

5. **Update Tests**
    - Update expected version in schema tests
    - Add migration test for new version

6. **Commit Everything**
   ```bash
   git add app-common-io/schemas/
   git add app-common-io/src/
   git commit -m "Bump RecycleBinDatabase to version 2 with migration"
   ```

## Current Schema Issue

**RecycleBinEntity** currently has uncommitted breaking schema changes:

- ID type: `String` → `Uuid`
- Path fields: `String` → `APath<*>`
- New field: `originalLookup: APathLookup<*>`

**Required actions:**

1. Bump version to 2
2. Implement migration from v1 → v2
3. Generate new schema file
4. Add migration tests
5. Test data transformation logic

## Benefits

✅ **Automatic Detection**: Schema changes show up as file changes
✅ **Version Enforcement**: Tests fail if schema changes without version bump
✅ **Code Review**: Schema diffs visible in PRs
✅ **Migration Safety**: Validation tests ensure migrations work correctly
✅ **Historical Tracking**: Git history shows all schema evolution
✅ **CI/CD Integration**: Automated checks prevent accidental schema changes

## References

- [Room Schema Export Documentation](https://developer.android.com/training/data-storage/room/migrating-db-versions#export-schema)
- [Room Migration Testing](https://developer.android.com/training/data-storage/room/migrating-db-versions#test-migrations)
- Current Room version: 2.7.0