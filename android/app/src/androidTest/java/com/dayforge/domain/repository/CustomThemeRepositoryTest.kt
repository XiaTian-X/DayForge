package com.dayforge.domain.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dayforge.domain.model.GlobalColorTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real app-private files, isolated from both production storage and other test suites. */
@RunWith(AndroidJUnit4::class)
class CustomThemeRepositoryTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: CustomThemeRepository

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(application.cacheDir.toPath(), "theme-store-").toFile()
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        repository = CustomThemeRepository(context)
    }

    @After fun tearDown() {
        // Files.walk does not follow symbolic links created by the security cases.
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun theme(id: String = "custom") = GlobalColorTheme(
        id = id, name = "Custom theme", seedColor = "#123456",
        suitableForLight = true, suitableForDark = true, primary = "#987654"
    )

    @Test fun normalFilesRoundTripAcrossInstancesAndDeleteTruthfully() = runTest {
        val original = theme("自然 theme.v1")
        assertEquals(original.id, repository.addTheme(original).getOrThrow())
        val reopened = CustomThemeRepository(context)
        val expected = original.copy(isCustom = true)
        assertEquals(expected, reopened.getThemeById(original.id))
        assertEquals(expected, Json.decodeFromString<GlobalColorTheme>(reopened.getThemeJson(original.id)!!))
        assertEquals(listOf(expected), reopened.getAllCustomThemes())
        assertTrue(reopened.exists(original.id))
        assertTrue(reopened.deleteTheme(original.id).isSuccess)
        assertFalse(reopened.exists(original.id))
        assertTrue(reopened.deleteTheme(original.id).isFailure)
    }

    @Test fun traversalAndInvalidIdsCannotReadWriteOrDeleteOutsideFiles() = runTest {
        val sentinel = File(root, "sentinel.json")
        val original = Json.encodeToString(theme("sentinel"))
        sentinel.writeText(original)
        val invalid = listOf("../sentinel", "nested/../../sentinel", "/sentinel", "a\\b",
            ".", "..", "", " ", "bad\u0000id", "bad\nid", "界".repeat(81))
        for (id in invalid) {
            assertTrue(id, repository.addTheme(theme(id)).isFailure)
            assertNull(id, repository.getThemeById(id))
            assertNull(id, repository.getThemeJson(id))
            assertFalse(id, repository.exists(id))
            assertTrue(id, repository.deleteTheme(id).isFailure)
            assertEquals(original, sentinel.readText())
        }
        assertTrue(repository.getAllCustomThemes().isEmpty())
    }

    @Test fun symbolicLinksIncludingDanglingLinksAreNotFollowed() = runTest {
        val directory = File(root, "themes").apply { mkdirs() }
        val sentinel = File(root, "sentinel.json").apply {
            writeText(Json.encodeToString(theme("linked")))
        }
        val before = sentinel.readText()
        val linked = File(directory, "linked.json")
        Files.createSymbolicLink(linked.toPath(), sentinel.toPath())
        val dangling = File(directory, "dangling.json")
        val absent = File(root, "absent.json")
        Files.createSymbolicLink(dangling.toPath(), absent.toPath())
        for (id in listOf("linked", "dangling")) {
            assertNull(repository.getThemeById(id))
            assertNull(repository.getThemeJson(id))
            assertFalse(repository.exists(id))
            assertTrue(repository.addTheme(theme(id)).isFailure)
            assertTrue(repository.deleteTheme(id).isFailure)
        }
        assertTrue(repository.getAllCustomThemes().isEmpty())
        assertEquals(before, sentinel.readText())
        assertFalse(absent.exists())
        assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertTrue(Files.isSymbolicLink(dangling.toPath()))
    }

    @Test fun linkedThemeDirectoryCannotRedirectStorage() = runTest {
        val outside = File(root, "outside").apply { mkdirs() }
        val sentinel = File(outside, "custom.json").apply {
            writeText(Json.encodeToString(theme()))
        }
        val original = sentinel.readText()
        Files.createSymbolicLink(File(root, "themes").toPath(), outside.toPath())
        assertTrue(repository.addTheme(theme("new")).isFailure)
        assertTrue(repository.deleteTheme("custom").isFailure)
        assertNull(repository.getThemeJson("custom"))
        assertTrue(repository.getAllCustomThemes().isEmpty())
        assertEquals(original, sentinel.readText())
        assertFalse(File(outside, "new.json").exists())
    }

    @Test fun concurrentCreatesAcrossInstancesNeverOverwriteWinner() = runTest {
        val results = (1..16).map { index ->
            async {
                val candidate = theme().copy(name = "Candidate $index")
                candidate to CustomThemeRepository(context).addTheme(candidate)
            }
        }.awaitAll()
        val winners = results.filter { it.second.isSuccess }
        assertEquals(1, winners.size)
        val expected = winners.single().first.copy(isCustom = true)
        assertEquals(expected, repository.getThemeById("custom"))
        assertTrue(repository.addTheme(theme().copy(name = "Replacement")).isFailure)
        assertEquals(expected, repository.getThemeById("custom"))
        assertEquals(listOf("custom.json"), File(root, "themes").list()!!.toList())
    }

    @Test fun malformedOversizedAndMismatchedFilesDoNotHideValidThemes() = runTest {
        repository.addTheme(theme("valid")).getOrThrow()
        val directory = File(root, "themes")
        File(directory, "broken.json").writeText("{")
        File(directory, "mismatch.json").writeText(Json.encodeToString(theme("another")))
        File(directory, "large.json").writeText(" ".repeat(1024 * 1024 + 1))
        for (id in listOf("broken", "mismatch", "large")) {
            assertNull(repository.getThemeById(id))
            assertNull(repository.getThemeJson(id))
        }
        assertEquals(listOf("valid"), repository.getAllCustomThemes().map { it.id })
        val raw = "  " + Json.encodeToString(theme("raw")) + "\n"
        File(directory, "raw.json").writeText(raw)
        assertEquals(raw, repository.getThemeJson("raw"))
    }

    @Test fun oversizedAndPresetWritesLeaveNoInstalledOrTemporaryFiles() = runTest {
        assertTrue(repository.addTheme(theme().copy(name = "界".repeat(400_000))).isFailure)
        assertTrue(repository.addTheme(theme().copy(isDefault = true)).isFailure)
        assertTrue(repository.getAllCustomThemes().isEmpty())
        assertTrue(File(root, "themes").list()!!.isEmpty())
    }

    @Test fun interruptedTemporaryFileIsNeverPublishedAndDoesNotPreventRetry() = runTest {
        val directory = File(root, "themes").apply { mkdirs() }
        File(directory, ".theme-interrupted.tmp").writeText("{partial")
        assertNull(repository.getThemeById("custom"))
        assertTrue(repository.getAllCustomThemes().isEmpty())
        repository.addTheme(theme()).getOrThrow()
        assertEquals(listOf("custom"), repository.getAllCustomThemes().map { it.id })
        assertTrue(repository.deleteTheme("custom").isSuccess)
    }

    @Test fun failureBeforePublicationCleansTemporaryFileAndAllowsRetry() = runTest {
        repository.addTheme(theme("existing")).getOrThrow()
        val original = repository.getThemeJson("existing")
        val reads = AtomicInteger()
        val failingContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File {
                if (reads.incrementAndGet() == 2) throw IOException("Injected publication failure")
                return root
            }
        }
        // Fail the path recheck after bytes are flushed, before the atomic rename.
        val result = CustomThemeRepository(failingContext).addTheme(theme("new"))
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(listOf("existing.json"), File(root, "themes").list()!!.toList())
        assertEquals(original, repository.getThemeJson("existing"))
        repository.addTheme(theme("new")).getOrThrow()
        assertEquals(theme("new").copy(isCustom = true), repository.getThemeById("new"))
    }

    @Test fun cancelledCallDoesNotBecomeSuccessOrPublishAFile() = runTest {
        var cancellationObserved = false
        var returnedNormally = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try {
                repository.addTheme(theme())
                returnedNormally = true
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
        }
        job.join()
        assertTrue(cancellationObserved)
        assertFalse(returnedNormally)
        assertFalse(repository.exists("custom"))
    }
}
