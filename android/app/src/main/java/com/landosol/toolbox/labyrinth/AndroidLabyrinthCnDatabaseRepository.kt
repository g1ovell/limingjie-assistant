package com.landosol.toolbox.labyrinth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class AndroidLabyrinthCnDatabaseRepository(
    context: Context,
    client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val directory = File(context.filesDir, DATABASE_DIRECTORY)
    private val databaseFile = File(directory, DATABASE_FILE_NAME)
    private val metadataFile = File(directory, METADATA_FILE_NAME)
    private val updater = LabyrinthCnDatabaseUpdater(
        source = OkHttpSource(client, json),
        candidateFileFactory = {
            require(directory.exists() || directory.mkdirs()) { "Cannot create role database directory" }
            File.createTempFile("master-cn-candidate-", ".db", directory)
        },
        validator = AndroidCandidateValidator(),
        installer = AtomicInstaller(directory, databaseFile),
        metadataStore = JsonMetadataStore(directory, metadataFile, json),
        databaseAvailable = databaseFile::isFile,
    )

    fun updateIfNeeded(): LabyrinthCnDatabaseUpdateResult = updater.updateIfNeeded()

    fun currentDatabaseFile(): File? = databaseFile.takeIf(File::isFile)

    private class OkHttpSource(
        private val client: OkHttpClient,
        private val json: Json,
    ) : LabyrinthCnDatabaseSource {
        override fun fetchVersion(): LabyrinthCnDatabaseVersion {
            val request = Request.Builder().url(MANIFEST_URL).get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "manifest HTTP ${response.code}" }
                val body = response.body?.string() ?: error("empty manifest response")
                val manifest = json.decodeFromString<BuilderManifest>(body)
                return LabyrinthCnDatabaseVersion(manifest.version, manifest.sha256)
            }
        }

        override fun downloadDatabase(version: LabyrinthCnDatabaseVersion, destination: File) {
            val url = DATABASE_URL_TEMPLATE
                .replace("{version}", version.version)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "database HTTP ${response.code}" }
                val body = response.body ?: error("empty database response")
                destination.outputStream().buffered().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                    output.flush()
                }
                check(destination.length() >= MINIMUM_DATABASE_BYTES) { "downloaded database is too small" }
            }
        }
    }

    private class AndroidCandidateValidator : LabyrinthCnDatabaseCandidateValidator {
        override fun validate(candidate: File): LabyrinthCnDatabaseValidation {
            if (!candidate.isFile || candidate.length() < SQLITE_HEADER.size) {
                return LabyrinthCnDatabaseValidation.Invalid("文件不存在或为空")
            }
            val header = candidate.inputStream().use { it.readUpTo(SQLITE_HEADER.size) }
            if (!header.contentEquals(SQLITE_HEADER)) {
                return LabyrinthCnDatabaseValidation.Invalid("文件头不是SQLite format 3")
            }
            var database: SQLiteDatabase? = null
            return try {
                database = SQLiteDatabase.openDatabase(
                    candidate.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                val quickCheck = database.rawQuery("PRAGMA quick_check", emptyArray()).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (quickCheck != "ok") {
                    LabyrinthCnDatabaseValidation.Invalid("quick_check未通过")
                } else {
                    val schemas = inspectSchemas(database)
                    val namedResolution = LabyrinthMasterSchemaResolver().resolve(schemas)
                    val resolution = if (namedResolution.complete) {
                        namedResolution
                    } else {
                        LabyrinthMasterSemanticSchemaResolver().resolve(
                            schemas = schemas,
                            probe = AndroidMasterDataProbe(database),
                        )
                    }
                    if (resolution.complete) {
                        LabyrinthCnDatabaseValidation.Valid(resolution)
                    } else {
                        LabyrinthCnDatabaseValidation.Invalid(
                            "逻辑表识别不完整：${resolution.issues.joinToString("；")}",
                        )
                    }
                }
            } catch (failure: Exception) {
                LabyrinthCnDatabaseValidation.Invalid("SQLite只读校验异常：${failure.message ?: "未知错误"}")
            } finally {
                database?.close()
            }
        }

        private fun inspectSchemas(database: SQLiteDatabase): List<LabyrinthSqliteTableSchema> {
            val tableNames = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = ? AND name NOT LIKE ? ORDER BY name",
                arrayOf("table", "sqlite_%"),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (LabyrinthSqlIdentifier.isSafe(name)) add(name)
                    }
                }
            }
            return tableNames.mapNotNull { tableName ->
                val quoted = LabyrinthSqlIdentifier.quoteEnumerated(tableName)
                val definitions = database.rawQuery("PRAGMA table_info($quoted)", emptyArray()).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
                    buildList {
                        while (cursor.moveToNext()) {
                            val column = cursor.getString(nameIndex)
                            if (LabyrinthSqlIdentifier.isSafe(column)) {
                                add(
                                    LabyrinthSqliteColumnSchema(
                                        physicalName = column,
                                        declaredType = cursor.getString(typeIndex).orEmpty(),
                                        primaryKeyPosition = cursor.getInt(primaryKeyIndex),
                                    ),
                                )
                            }
                        }
                    }
                }
                definitions.takeIf(List<LabyrinthSqliteColumnSchema>::isNotEmpty)?.let {
                    LabyrinthSqliteTableSchema(
                        physicalName = tableName,
                        columns = it.mapTo(linkedSetOf(), LabyrinthSqliteColumnSchema::physicalName),
                        columnDefinitions = it,
                    )
                }
            }
        }

        private class AndroidMasterDataProbe(
            private val database: SQLiteDatabase,
        ) : LabyrinthMasterDataProbe {
            override fun primaryKeyValuesMatching(
                schema: LabyrinthSqliteTableSchema,
                candidates: Set<Long>,
            ): Set<Long> {
                val primaryKey = schema.singleIntegerPrimaryKey ?: return emptySet()
                if (candidates.isEmpty()) return emptySet()
                val table = LabyrinthSqlIdentifier.quoteEnumerated(schema.physicalName)
                val column = LabyrinthSqlIdentifier.quoteEnumerated(primaryKey.physicalName)
                return candidates.chunked(MAX_BIND_ARGUMENTS).flatMapTo(linkedSetOf()) { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    database.rawQuery(
                        "SELECT DISTINCT $column FROM $table WHERE $column IN ($placeholders)",
                        chunk.map(Long::toString).toTypedArray(),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getLong(0))
                        }
                    }
                }
            }

            override fun integerValuesForPrimaryKeys(
                schema: LabyrinthSqliteTableSchema,
                primaryKeys: Set<Long>,
                range: LongRange,
            ): Set<Long> {
                val primaryKey = schema.singleIntegerPrimaryKey ?: return emptySet()
                if (primaryKeys.isEmpty()) return emptySet()
                val table = LabyrinthSqlIdentifier.quoteEnumerated(schema.physicalName)
                val primaryKeyColumn = LabyrinthSqlIdentifier.quoteEnumerated(primaryKey.physicalName)
                val integerColumns = schema.columnDefinitions
                    .filter { it.normalizedType == "INTEGER" }
                    .map { LabyrinthSqlIdentifier.quoteEnumerated(it.physicalName) }
                if (integerColumns.isEmpty()) return emptySet()
                val projection = integerColumns.joinToString(",")
                return primaryKeys.chunked(MAX_BIND_ARGUMENTS).flatMapTo(linkedSetOf()) { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    database.rawQuery(
                        "SELECT $projection FROM $table WHERE $primaryKeyColumn IN ($placeholders)",
                        chunk.map(Long::toString).toTypedArray(),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                for (index in 0 until cursor.columnCount) {
                                    if (!cursor.isNull(index)) {
                                        cursor.getLong(index).takeIf(range::contains)?.let(::add)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            private companion object {
                const val MAX_BIND_ARGUMENTS = 400
            }
        }
    }

    private class AtomicInstaller(
        private val directory: File,
        private val target: File,
    ) : LabyrinthCnDatabaseInstaller {
        override fun install(candidate: File) {
            val resolvedDirectory = directory.canonicalFile
            require(candidate.canonicalFile.parentFile == resolvedDirectory) { "Candidate escaped database directory" }
            require(target.canonicalFile.parentFile == resolvedDirectory) { "Target escaped database directory" }
            require(candidate.isFile) { "Candidate disappeared before install" }
            if (target.isFile) {
                Files.copy(
                    target.toPath(),
                    File(directory, BACKUP_FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            Files.move(
                candidate.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private class JsonMetadataStore(
        private val directory: File,
        private val target: File,
        private val json: Json,
    ) : LabyrinthCnDatabaseMetadataStore {
        override fun load(): LabyrinthCnDatabaseMetadata? =
            target.takeIf(File::isFile)?.bufferedReader()?.use { reader ->
                json.decodeFromString<LabyrinthCnDatabaseMetadata>(reader.readText())
            }

        override fun save(metadata: LabyrinthCnDatabaseMetadata) {
            require(directory.exists() || directory.mkdirs()) { "Cannot create metadata directory" }
            val temporary = File.createTempFile("master-cn-metadata-", ".json", directory)
            try {
                temporary.bufferedWriter().use { writer -> writer.write(json.encodeToString(metadata)) }
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    @Serializable
    private data class BuilderManifest(
        @SerialName("db_version") val version: String,
        @SerialName("checksum_sha256") val sha256: String,
    )

    private companion object {
        const val MANIFEST_URL =
            "https://github.com/wbero/autopcr-db-builder/releases/latest/download/manifest.json"
        const val DATABASE_URL_TEMPLATE =
            "https://github.com/wbero/autopcr-db-builder/releases/download/db-v{version}/{version}.db"
        const val DATABASE_DIRECTORY = "labyrinth/master-cn"
        const val DATABASE_FILE_NAME = "master_cn.db"
        const val BACKUP_FILE_NAME = "master_cn.previous.db"
        const val METADATA_FILE_NAME = "master_cn.metadata.json"
        const val MINIMUM_DATABASE_BYTES = 1_000_000L
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
