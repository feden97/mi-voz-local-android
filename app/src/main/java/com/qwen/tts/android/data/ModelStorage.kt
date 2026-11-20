package com.qwen.tts.android.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Remove one variant's directory entries, preserving same-directory shared codecs. */
internal class ModelStorage(private val root: File, private val variants: List<ModelVariant>) {
    data class DiskUsage(val device: Long, val inode: Long, val links: Long, val bytes: Long)

    private fun file(variant: ModelVariant, name: String): File {
        require(variant.storageGroup.matches(Regex("[A-Za-z0-9_]+")))
        require(name == File(name).name && '/' !in name && '\\' !in name)
        return PrivateFiles.child(root, File(File(root, variant.storageGroup), name))
    }

    private fun otherTalkerPresent(variant: ModelVariant): Boolean = variants.any {
        it.id != variant.id && it.storageGroup == variant.storageGroup &&
            (file(it, it.talker.name).exists() || file(it, "${it.talker.name}.download").exists())
    }

    fun removalPlan(variant: ModelVariant): List<File> {
        val names = mutableListOf(variant.talker.name, "${variant.talker.name}.download")
        if (!otherTalkerPresent(variant)) names += listOf(variant.tokenizer.name, "${variant.tokenizer.name}.download")
        return names.map { file(variant, it) }.filter { it.exists() }
    }

    /** Allocated bytes freed when unlinking this variant's files from the model directory. */
    fun reclaimableBytesByVariant(readDiskUsage: (File) -> DiskUsage? = ::statDiskUsage): Map<String, Long> =
        variants.mapNotNull { variant ->
            val files = removalPlan(variant)
            if (files.isEmpty()) return@mapNotNull null
            val filesByInode = files.mapNotNull(readDiskUsage).groupBy { it.device to it.inode }
            val bytes = filesByInode.values.sumOf { links ->
                val usage = links.first()
                if (links.size.toLong() == usage.links) usage.bytes else 0L
            }
            variant.id to bytes
        }.toMap()

    private fun statDiskUsage(file: File): DiskUsage? {
        val stat = try {
            Os.lstat(file.path)
        } catch (_: ErrnoException) {
            return null
        }
        if (!OsConstants.S_ISREG(stat.st_mode)) return null
        // Linux reports st_blocks in units of 512 bytes, including for partial downloads.
        return DiskUsage(stat.st_dev, stat.st_ino, stat.st_nlink, stat.st_blocks * 512)
    }

    fun remove(variant: ModelVariant, invalidate: (File) -> Unit = {}) {
        removalPlan(variant).forEach { target ->
            PrivateFiles.deleteFile(root, target)
            invalidate(target)
        }
        // Only remove an empty group; other variants and unknown files are untouched.
        File(root, variant.storageGroup).takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
    }
}
