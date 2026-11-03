package cc.lem0n.androidrichpresence.presence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedTokenVault(
    context: Context,
    private val applicationId: String = DEFAULT_APPLICATION_ID,
    fileName: String = "discord-link.bin",
    private val keyAlias: String = "discord-presence-link-v1",
) : TokenVault {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
    private val aad = "discord-presence:1:$applicationId".toByteArray(Charsets.UTF_8)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun load(): TokenLoad {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return TokenLoad.Empty
        return try {
            val encrypted = file.openRead().use { it.readBytes() }
            require(encrypted.size in 30..131_072)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
            require(encrypted[0] == 1.toByte())
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(encrypted, 13, encrypted.size - 13)
            try {
                DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                    when (input.readInt()) {
                        0 -> {
                            val expiresAt = input.readLong()
                            val access = input.secret()
                            val refresh = input.secret()
                            require(input.available() == 0 && expiresAt > 0)
                            TokenLoad.Stored(SecretTokenPair(access, refresh, expiresAt))
                        }
                        else -> TokenLoad.RequiresLink
                    }
                }
            } finally { plaintext.fill(0) }
        } catch (_: Exception) { TokenLoad.RequiresLink }
    }
    private fun DataInputStream.secret(): ByteArray {
        val size = readInt()
        require(size in 1..32_768 && size <= available())
        return ByteArray(size).also { readFully(it) }
    }
    override fun replace(tokens: SecretTokenPair): Boolean = write { output ->
        output.writeInt(0)
        output.writeLong(tokens.expiresAt)
        output.writeInt(tokens.access.size); output.write(tokens.access)
        output.writeInt(tokens.refresh.size); output.write(tokens.refresh)
    }
    override fun markRefreshing(): Boolean = write { it.writeInt(1) }
    override fun erase(): Boolean = try {
        file.delete()
        syncDirectory()
        listOf("", ".bak", ".new").none { File(file.baseFile.path + it).exists() }
    } catch (_: Exception) { false }

    private fun syncDirectory() {
        val descriptor = Os.open(file.baseFile.parentFile!!.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun write(body: (DataOutputStream) -> Unit): Boolean {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(body)
        val plaintext = buffer.toByteArray()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            val output = file.startWrite()
            try {
                output.write(1)
                output.write(cipher.iv)
                output.write(encrypted)
                output.fd.sync()
                file.finishWrite(output)
                syncDirectory()
                val committed = file.openRead().use { it.readBytes() }
                committed.contentEquals(byteArrayOf(1) + cipher.iv + encrypted)
            } catch (_: Exception) { file.failWrite(output); false }
        } catch (_: Exception) { false }
        finally { plaintext.fill(0); buffer.reset() }
    }
}
