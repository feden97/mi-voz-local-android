package com.qwen.tts.android.data

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal object PrivateFiles {
    fun child(root: File, target: File): File {
        val base = root.canonicalFile.toPath()
        val resolved = target.canonicalFile.toPath()
        require(resolved != base && resolved.startsWith(base)) { "La ruta no pertenece a los archivos de la app." }
        return target
    }

    fun deleteFile(root: File, target: File) {
        child(root, target)
        require(!target.isDirectory) { "Se esperaba un archivo." }
        Files.deleteIfExists(target.toPath())
    }

    fun deleteTree(root: File, target: File) {
        child(root, target)
        if (!target.exists()) return
        // No FOLLOW_LINKS: a nested symlink never traverses another directory.
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
