package cc.lem0n.androidrichpresence.presence

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore

class EncryptedTokenVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val filename = "test-discord-link.bin"
    private val alias = "test-discord-presence-link"
    private val file get() = File(context.noBackupFilesDir, filename)
    private fun vault(id: String = DEFAULT_APPLICATION_ID) = EncryptedTokenVault(context, id, filename, alias)
    private fun tokens(access: String = "FAKE-ACCESS-ONLY", refresh: String = "FAKE-REFRESH-ONLY") =
        SecretTokenPair(access.toByteArray(), refresh.toByteArray(), 9_000_000)
    @Before fun prepare() { cleanup() }
    @After fun cleanup() {
        vault().erase()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
    @Test fun encryptedRoundtripUsesNoBackupDirectoryAndNoPlaintext() {
        assertTrue(vault().replace(tokens()))
        assertEquals(context.noBackupFilesDir.canonicalFile, file.parentFile?.canonicalFile)
        val bytes = file.readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("FAKE-ACCESS-ONLY"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("FAKE-REFRESH-ONLY"))
        val restored = (vault().load() as TokenLoad.Stored).tokens
        assertArrayEquals("FAKE-ACCESS-ONLY".toByteArray(), restored.access)
        assertArrayEquals("FAKE-REFRESH-ONLY".toByteArray(), restored.refresh)
        assertEquals(9_000_000, restored.expiresAt)
        restored.destroy()
    }
    @Test fun replacementHasFreshIvAndRestoresOnlyNewPair() {
        assertTrue(vault().replace(tokens()))
        val first = file.readBytes()
        assertTrue(vault().replace(tokens("FAKE-NEW-ACCESS", "FAKE-NEW-REFRESH")))
        val second = file.readBytes()
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        val restored = (vault().load() as TokenLoad.Stored).tokens
        assertArrayEquals("FAKE-NEW-ACCESS".toByteArray(), restored.access)
        assertArrayEquals("FAKE-NEW-REFRESH".toByteArray(), restored.refresh)
        assertFalse(File(file.path + ".bak").exists())
        restored.destroy()
    }
    @Test fun appIdMismatchFailsClosed() {
        assertTrue(vault().replace(tokens()))
        assertEquals(TokenLoad.RequiresLink, vault("1567132201588367361").load())
    }
    @Test fun corruptionAndMissingKeyRequireRelink() {
        assertTrue(vault().replace(tokens()))
        val original = file.readBytes()
        file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertEquals(TokenLoad.RequiresLink, vault().load())
        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertEquals(TokenLoad.RequiresLink, vault().load())
    }
    @Test fun refreshMarkerSurvivesRestartUntilReplacementCommits() {
        assertTrue(vault().replace(tokens()))
        assertTrue(vault().markRefreshing())
        assertEquals(TokenLoad.RequiresLink, vault().load())
        assertTrue(vault().replace(tokens("FAKE-ROTATED", "FAKE-ROTATED-REFRESH")))
        assertTrue(vault().load() is TokenLoad.Stored)
        assertTrue(vault().erase())
        assertEquals(TokenLoad.Empty, vault().load())
    }
}
