package com.qwen.tts.android.data

import java.io.File

/** Remove one variant's directory entries, preserving same-directory shared codecs. */
internal class ModelStorage(private val root: File, private val variants: List<ModelVariant>) {
    private fun file(variant: ModelVariant, name: String): File {
        require(variant.storageGroup.matches(Regex("[A-Za-z0-9_]+")))
        require(name == File(name).name && '/' !in name && '\\' !in name)
        return PrivateFiles.child(root, File(File(root, variant.storageGroup), name))
    }

    private fun otherTalkerPresent(variant: ModelVariant): Boolean = variants.any {
        it.id != variant.id && it.storageGroup == variant.storageGroup &&
            (file(it, it.talker.name).exists() || file(it, "${it.talker.name}.download").exists())
    }

    fun hasFiles(variant: ModelVariant): Boolean =
        file(variant, variant.talker.name).exists() || file(variant, "${variant.talker.name}.download").exists() ||
            (!otherTalkerPresent(variant) && (file(variant, variant.tokenizer.name).exists() ||
                file(variant, "${variant.tokenizer.name}.download").exists()))

    fun removalPlan(variant: ModelVariant): List<File> {
        val names = mutableListOf(variant.talker.name, "${variant.talker.name}.download")
        if (!otherTalkerPresent(variant)) names += listOf(variant.tokenizer.name, "${variant.tokenizer.name}.download")
        return names.map { file(variant, it) }.filter { it.exists() }
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
