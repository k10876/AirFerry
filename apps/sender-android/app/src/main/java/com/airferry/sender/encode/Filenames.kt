package com.airferry.sender.encode

/** Filename sanitizing — mirrors `apps/sender/src/storage/textDrafts.ts`. */
object Filenames {
    fun sanitize(name: String): String {
        val trimmed = name.trim().ifEmpty { "unnamed" }
        val noPath = trimmed.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = noPath.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "unnamed" }
    }

    fun normalizeTxt(name: String): String {
        val s = sanitize(name)
        return if (s.endsWith(".txt", ignoreCase = true)) s else "$s.txt"
    }
}
