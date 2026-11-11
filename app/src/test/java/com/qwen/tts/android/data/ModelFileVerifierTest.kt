package com.qwen.tts.android.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abc = ModelFile("model.gguf", "https://example.invalid/model.gguf", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test
    fun validatesRealBytesAndRejectsSameSizeWriteWithRestoredModificationTime() {
        val file = temporary.newFile().apply { writeText("abc") }
        val verifier = ModelFileVerifier()
        assertTrue(verifier.isVerified(file, abc))
        val modified = Files.getLastModifiedTime(file.toPath())
        file.writeText("xyz")
        Files.setLastModifiedTime(file.toPath(), modified)
        assertFalse(verifier.isVerified(file, abc))
        file.writeText("abc")
        assertTrue(verifier.verifyFresh(file, abc))
        file.delete()
        assertFalse(verifier.isVerified(file, abc))
    }

    @Test
    fun unchangedReceiptSurvivesReopeningButExplicitFreshCheckHashesAgain() {
        val file = temporary.newFile().apply { writeText("abc") }
        val cache = temporary.newFolder()
        val stamp = ModelFileFingerprint(3, "mtime", "ctime", "inode")
        var hashes = 0
        fun verifier() = ModelFileVerifier(cache, { hashes++; abc.sha256 }, { stamp })
        val first = verifier()
        assertTrue(first.isVerified(file, abc))
        assertTrue(first.isVerified(file, abc))
        assertTrue(verifier().isVerified(file, abc))
        assertEquals(1, hashes)
        assertTrue(first.verifyFresh(file, abc))
        assertEquals(2, hashes)
        first.invalidate(file)
        assertTrue(verifier().isVerified(file, abc))
        assertEquals(3, hashes)
    }

    @Test
    fun metadataOrExpectedDigestChangesInvalidateMemoryAndDiskReceipts() {
        val file = temporary.newFile().apply { writeText("abc") }
        val cache = temporary.newFolder()
        var stamp = ModelFileFingerprint(3, "mtime", "ctime", "inode")
        var hashes = 0
        val verifier = ModelFileVerifier(cache, { hashes++; abc.sha256 }, { stamp })
        assertTrue(verifier.isVerified(file, abc))
        stamp = stamp.copy(changed = "changed while mtime was restored")
        assertTrue(verifier.isVerified(file, abc))
        stamp = stamp.copy(fileKey = "replacement inode")
        assertTrue(verifier.isVerified(file, abc))
        stamp = stamp.copy(modified = "new mtime")
        assertTrue(verifier.isVerified(file, abc))
        assertEquals(4, hashes)
        assertFalse(verifier.isVerified(file, abc.copy(sha256 = "0".repeat(64))))
        assertEquals(5, hashes)
        stamp = stamp.copy(size = 4)
        assertFalse(verifier.isVerified(file, abc))
        assertEquals(5, hashes)
    }

    @Test
    fun unsupportedChangeTimeFallsBackToHashAndChangingFileNeverGetsReceipt() {
        val file = temporary.newFile().apply { writeText("abc") }
        val cache = temporary.newFolder()
        var stamp = ModelFileFingerprint(3, "mtime", null, "inode")
        var hashes = 0
        val verifier = ModelFileVerifier(cache, { hashes++; abc.sha256 }, { stamp })
        assertTrue(verifier.isVerified(file, abc))
        assertTrue(verifier.isVerified(file, abc))
        assertEquals(2, hashes)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        stamp = stamp.copy(changed = "before")
        val changing = ModelFileVerifier(cache, { stamp = stamp.copy(changed = "after"); abc.sha256 }, { stamp })
        assertFalse(changing.isVerified(file, abc))
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun damagedReceiptCannotBypassHashVerification() {
        val file = temporary.newFile().apply { writeText("abc") }
        val cache = temporary.newFolder()
        val stamp = ModelFileFingerprint(3, "mtime", "ctime", "inode")
        assertTrue(ModelFileVerifier(cache, { abc.sha256 }, { stamp }).isVerified(file, abc))
        requireNotNull(cache.listFiles()).single().writeBytes(byteArrayOf(0, 1, 2))
        var hashes = 0
        assertFalse(ModelFileVerifier(cache, { hashes++; "0".repeat(64) }, { stamp }).isVerified(file, abc))
        assertEquals(1, hashes)
    }
}
