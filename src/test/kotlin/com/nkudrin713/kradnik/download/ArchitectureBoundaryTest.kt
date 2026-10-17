package com.nkudrin713.kradnik.download

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchitectureBoundaryTest {
    private val root = Path.of("src/main/kotlin/com/nkudrin713/kradnik")
    private val scenarios = setOf("choice", "service", "processing", "playlist", "single", "domain", "source", "delivery", "progress", "identity")
    private val contracts = setOf("domain", "source", "delivery", "progress")

    @Test
    fun scenariosDoNotDependOnTransportPersistenceOrSourceImplementations() {
        val forbidden = Regex(
            "^(com\\.pengrad\\.|jakarta\\.persistence\\.|org\\.hibernate\\.|org\\.springframework\\.data\\.|" +
                "com\\.fasterxml\\.|com\\.nkudrin713\\.kradnik\\.(telegram\\.|persistence\\.|process\\.|ytdlp\\.|" +
                "download\\.(telegram|repository|diagnostics|youtube|instagram|vk|threads)\\.))",
        )
        val violations = downloadFiles().flatMap { path ->
            if (owner(path) !in scenarios) emptyList() else imports(path).filter(forbidden::containsMatchIn).map { "$path: $it" }
        }
        assertEquals(emptyList(), violations, "Scenario dependencies must point to contracts and shared operations")
    }

    @Test
    fun sharedContractsDoNotDependOnTheScenariosThatUseThem() {
        val allowed = Regex(
            "^com\\.nkudrin713\\.kradnik\\.(download\\.(domain|source|delivery|progress)\\.|" +
                "download\\.platform\\.(DownloadPlatform|PlatformDownloadSpecs)$|localization\\.BotLanguage$)",
        )
        val violations = downloadFiles().flatMap { path ->
            if (owner(path) !in contracts) {
                emptyList()
            } else {
                imports(path).filter { it.startsWith("com.nkudrin713.kradnik.") && !allowed.containsMatchIn(it) }.map { "$path: $it" }
            }
        }
        assertEquals(emptyList(), violations, "Contract data must not depend on execution or infrastructure")
    }

    @Test
    fun jobAndMenuScenariosDoNotDependOnEachOtherThroughTheWorkerRuntime() {
        val forbidden = "com.nkudrin713.kradnik.download.processing."
        val violations = downloadFiles().flatMap { path ->
            if (owner(path) !in setOf("service", "choice", "playlist", "single")) emptyList() else imports(path).filter { it.startsWith(forbidden) }.map { "$path: $it" }
        }
        assertEquals(emptyList(), violations, "Execution events and cancellation must use shared contracts")
    }

    @Test
    fun telegramEntryPointsDoNotBuildSourceRequestsOrVersionCacheKeys() {
        val forbidden = Regex("com\\.nkudrin713\\.kradnik\\.(ytdlp|process|download\\.(youtube|instagram|vk|threads|repository|identity|video))\\.")
        val entryPoints = files(root.resolve("telegram")).filter { path ->
            path.parent.fileName.toString() == "handler" || path.fileName.toString() in setOf("DownloadChoiceCoordinator.kt", "TelegramDownloadStarter.kt")
        }
        val violations = entryPoints.flatMap { path ->
            imports(path).filter(forbidden::containsMatchIn).map { "$path: $it" }
        }
        assertEquals(emptyList(), violations, "Telegram entry points must delegate download policy and persistence")
    }

    private fun owner(path: Path): String = root.resolve("download").relativize(path).getName(0).toString()
    private fun downloadFiles(): List<Path> = files(root.resolve("download"))
    private fun files(directory: Path): List<Path> = Files.walk(directory).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
    private fun imports(path: Path): List<String> = Files.readAllLines(path).filter { it.startsWith("import ") }.map { it.removePrefix("import ") }
}
