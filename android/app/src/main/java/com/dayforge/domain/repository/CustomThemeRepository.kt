package com.dayforge.domain.repository

import android.content.Context
import android.util.Log
import com.dayforge.domain.model.GlobalColorTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private theme files. Imported IDs are data, never relative paths. */
@Singleton
class CustomThemeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun getAllCustomThemes(): List<GlobalColorTheme> = access {
        val directory = themeDirectory()
        val files = directory.listFiles() ?: throw IOException("Cannot list themes")
        files.filter { it.extension == "json" }.sortedBy { it.name }.mapNotNull { file ->
            safely {
                val checked = themeFile(file.nameWithoutExtension)
                decodeTheme(checked, readJson(checked)).copy(isCustom = true, isDefault = false)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    suspend fun addTheme(theme: GlobalColorTheme): Result<String> = access {
        require(!theme.isDefault) { "Cannot add preset theme as custom" }
        val file = themeFile(theme.id)
        require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "Theme already exists" }
        val bytes = json.encodeToString(theme.copy(isCustom = true, isDefault = false))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "Theme file is too large" }

        // Publish only after a complete, flushed write. A stopped process may leave
        // an unreferenced .tmp file, which is never discovered as an installed theme.
        val temporary = File.createTempFile(".theme-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            // All instances share the lock. Never copy partial bytes to the live file.
            check(themeFile(theme.id) == file)
            require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "Theme already exists" }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE)
            theme.id
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "Could not remove an uncommitted theme file")
            }
        }
    }

    suspend fun deleteTheme(themeId: String): Result<Unit> = access {
        val file = themeFile(themeId)
        require(!decodeTheme(file, readJson(file)).isDefault) { "Cannot delete preset theme" }
        Files.delete(file.toPath())
    }

    suspend fun getThemeById(themeId: String): GlobalColorTheme? = access {
        val file = themeFile(themeId)
        decodeTheme(file, readJson(file)).copy(isCustom = true, isDefault = false)
    }.getOrNull()

    /** Preserve raw JSON for normal export, but never export a mismatched ID. */
    suspend fun getThemeJson(themeId: String): String? = access {
        val file = themeFile(themeId)
        readJson(file).also { decodeTheme(file, it) }
    }.getOrNull()

    suspend fun exists(themeId: String): Boolean = access {
        Files.isRegularFile(themeFile(themeId).toPath(), NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun themeDirectory(): File {
        // filesDir can have a system-managed alias; only its trusted parent is resolved.
        val directory = File(context.filesDir.canonicalFile, "themes")
        require(!Files.isSymbolicLink(directory.toPath())) { "Invalid theme directory" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create theme directory")
        }
        require(directory.canonicalFile == directory) { "Invalid theme directory" }
        return directory
    }

    private fun themeFile(id: String): File {
        require(id.isNotBlank() && id != "." && id != ".." &&
            id.none { it == '/' || it == '\\' || it.isISOControl() } &&
            id.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES) { "Invalid theme ID" }
        val directory = themeDirectory()
        val file = File(directory, "$id.json")
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file) {
            "Invalid theme path"
        }
        return file
    }

    private fun decodeTheme(file: File, content: String): GlobalColorTheme =
        json.decodeFromString<GlobalColorTheme>(content).also {
            require(it.id == file.nameWithoutExtension) { "Theme ID does not match its file" }
        }

    private fun readJson(file: File): String {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Theme not found" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_JSON_BYTES) { "Theme file is too large" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun <T> access(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            fileMutex.withLock { safely { block() } }
        }

    private inline fun <T> safely(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Imported content, names and arbitrary exception messages stay out of logs.
        Log.w(TAG, "Theme storage operation failed: ${error.javaClass.simpleName}")
        Result.failure(error)
    }

    private companion object {
        const val TAG = "CustomThemeRepository"
        const val MAX_ID_BYTES = 240
        const val MAX_JSON_BYTES = 1024 * 1024
        // One app process; separate injected/test instances must also serialize.
        val fileMutex = Mutex()
    }
}
