package com.qwen.tts.android.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID

internal data class ModelFileFingerprint(
    val size: Long,
    val modified: String,
    val changed: String?,
    val fileKey: String?,
) {
    // ctime catches same-size writes even when mtime is restored. Fail closed on providers
    // without it or a stable file identity: those files receive a full SHA-256 each time.
    val cacheable: Boolean get() = changed != null && fileKey != null
}

/** Private integrity receipts save rereading multi-GB weights when their identity is unchanged. */
internal class ModelFileVerifier(
    private val cacheDir: File? = null,
    private val hashFile: (File) -> String = ::modelSha256,
    private val fingerprint: (File) -> ModelFileFingerprint? = ::modelFingerprint,
) {
    private data class Receipt(val expectedHash: String, val stamp: ModelFileFingerprint)
    private val verified = mutableMapOf<String, Receipt>()

    @Synchronized
    fun isVerified(file: File, expected: ModelFile): Boolean {
        val key = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val stamp = fingerprint(file)
        if (stamp == null || stamp.size != expected.sizeBytes) {
            invalidateKey(key)
            return false
        }
        if (stamp.cacheable) {
            val receipt = verified[key] ?: readReceipt(key)
            if (receipt?.expectedHash == expected.sha256 && receipt.stamp == stamp) {
                verified[key] = receipt
                return true
            }
        }
        return verifyFresh(file, expected)
    }

    /** Use for a finished download or repair; never trust a previous receipt for new bytes. */
    @Synchronized
    fun verifyFresh(file: File, expected: ModelFile): Boolean {
        val key = runCatching { file.canonicalPath }.getOrNull() ?: return false
        invalidateKey(key)
        val before = fingerprint(file) ?: return false
        if (before.size != expected.sizeBytes) return false
        val actual = runCatching { hashFile(file) }.getOrNull() ?: return false
        val after = fingerprint(file) ?: return false
        if (before != after || actual != expected.sha256) return false
        if (after.cacheable) {
            val receipt = Receipt(expected.sha256, after)
            verified[key] = receipt
            writeReceipt(key, receipt)
        }
        return true
    }

    @Synchronized
    fun invalidate(file: File) {
        runCatching { file.canonicalPath }.getOrNull()?.let(::invalidateKey)
    }

    private fun invalidateKey(key: String) {
        verified.remove(key)
        receiptFile(key)?.let { runCatching { it.delete() } }
    }

    private fun receiptFile(key: String): File? = cacheDir?.let {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        File(it, digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } + ".verified")
    }

    private fun readReceipt(key: String): Receipt? = runCatching {
        val file = receiptFile(key) ?: return@runCatching null
        if (!file.isFile || file.length() !in 1..4096) return@runCatching null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != 1) return@use null
            val hash = input.readUTF()
            val stamp = ModelFileFingerprint(input.readLong(), input.readUTF(), input.readUTF(), input.readUTF())
            if (input.read() != -1 || !hash.matches(Regex("[0-9a-f]{64}")) || !stamp.cacheable) null
            else Receipt(hash, stamp)
        }
    }.getOrNull()

    private fun writeReceipt(key: String, receipt: Receipt) {
        val target = receiptFile(key) ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val pending = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                DataOutputStream(pending.outputStream().buffered()).use { output ->
                    output.writeInt(1)
                    output.writeUTF(receipt.expectedHash)
                    output.writeLong(receipt.stamp.size)
                    output.writeUTF(receipt.stamp.modified)
                    output.writeUTF(requireNotNull(receipt.stamp.changed))
                    output.writeUTF(requireNotNull(receipt.stamp.fileKey))
                }
                try {
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                pending.delete()
            }
        }
    }
}

private fun modelFingerprint(file: File): ModelFileFingerprint? = runCatching {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    if (!attributes.isRegularFile) return@runCatching null
    val changed = runCatching { Files.getAttribute(file.toPath(), "unix:ctime") as? FileTime }.getOrNull()
    ModelFileFingerprint(attributes.size(), attributes.lastModifiedTime().toString(), changed?.toString(), attributes.fileKey()?.toString())
}.getOrNull()

private fun modelSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(1024 * 1024).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
