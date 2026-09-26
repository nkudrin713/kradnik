package com.nkudrin713.kradnik.admin

import java.nio.file.Files
import java.nio.file.Path

/** Resolves the process's memory cgroup once; reads only two small kernel counters per sample. */
class CgroupMemory(private val proc: Path = Path.of("/proc/self")) {
    private val location: Pair<Path, Boolean>? = runCatching { locate() }.getOrNull()

    fun read(): ContainerMemory {
        val (directory, v2) = location ?: return ContainerMemory()
        fun number(name: String): Long? = runCatching { Files.readString(directory.resolve(name)).trim().toLongOrNull()?.takeIf { it >= 0 } }.getOrNull()
        val used = number(if (v2) "memory.current" else "memory.usage_in_bytes")
        val limit = number(if (v2) "memory.max" else "memory.limit_in_bytes")?.takeIf { it > 0 && (v2 || it < Long.MAX_VALUE / 2) }
        return ContainerMemory(used, limit)
    }

    private fun locate(): Pair<Path, Boolean>? {
        val groups = Files.readAllLines(proc.resolve("cgroup")).map { it.split(':', limit = 3) }.filter { it.size == 3 }
        val mounts = Files.readAllLines(proc.resolve("mountinfo"))
        // Prefer the actual v1 memory controller on hybrid hosts.
        for (v2 in listOf(false, true)) {
            val group = groups.firstOrNull { if (v2) it[0] == "0" && it[1].isEmpty() else "memory" in it[1].split(',') } ?: continue
            for (mount in mounts) {
                val parts = mount.split(" - ", limit = 2)
                if (parts.size != 2) continue
                val fields = parts[0].split(' ')
                val fs = parts[1].split(' ')
                if (fields.size < 5 || fs.size < 3) continue
                if (if (v2) fs[0] != "cgroup2" else fs[0] != "cgroup" || "memory" !in fs[2].split(',')) continue
                val root = Path.of(unescape(fields[3]))
                val path = Path.of(group[2])
                if (!path.startsWith(root)) continue
                val directory = Path.of(unescape(fields[4])).resolve(root.relativize(path)).normalize()
                return directory to v2
            }
        }
        return null
    }

    private fun unescape(value: String): String = Regex("\\\\([0-7]{3})").replace(value) { it.groupValues[1].toInt(8).toChar().toString() }
}

data class ContainerMemory(val used: Long? = null, val limit: Long? = null)
