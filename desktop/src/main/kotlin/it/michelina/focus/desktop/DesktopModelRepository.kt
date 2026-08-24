package it.michelina.focus.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal object DesktopModelRepository {
    fun materialize(resourcePath: String): Path {
        val target = modelDirectory().resolve(resourcePath.substringAfterLast('/'))
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target

        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            val resource = requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
                "Model resource not found: $resourcePath"
            }
            resource.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            runCatching {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun modelDirectory(): Path {
        val override = System.getProperty("melina.modelDir")
            ?: System.getProperty("michelina.modelDir")
        if (!override.isNullOrBlank()) return Path.of(override)
        val os = System.getProperty("os.name").lowercase()
        val userHome = Path.of(System.getProperty("user.home"))
        return when {
            os.contains("mac") -> userHome.resolve("Library/Caches/Melina/models")
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData.isNullOrBlank()) {
                    userHome.resolve("AppData/Local/Melina/models")
                } else {
                    Path.of(localAppData).resolve("Melina/models")
                }
            }
            else -> {
                val cacheHome = System.getenv("XDG_CACHE_HOME")
                if (cacheHome.isNullOrBlank()) {
                    userHome.resolve(".cache/melina/models")
                } else {
                    Path.of(cacheHome).resolve("melina/models")
                }
            }
        }
    }
}
