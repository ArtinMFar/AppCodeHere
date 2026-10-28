package com.colabmcp.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Regression: ngrok-java's JNI_OnLoad returned JNI_VERSION_1_8, which Android rejects, so the
 * tunnel failed with "runtime not initialized". The build patches it to JNI_VERSION_1_6.
 */
class NgrokNativeLibTest {
    private fun lib(abi: String) = File("build/generated/ngrok/jniLibs/$abi/libngrok_java.so").readBytes()

    private fun count(bytes: ByteArray, hex: String): Int {
        val p = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return (0..bytes.size - p.size).count { i -> p.indices.all { bytes[i + it] == p[it] } }
    }

    @Test
    fun jniOnLoadReturnsVersion16() {
        val arm64 = lib("arm64-v8a")
        assertEquals(0, count(arm64, "000180522000a072"))
        assertEquals(1, count(arm64, "c00080522000a072"))
        val armv7 = lib("armeabi-v7a")
        assertEquals(0, count(armv7, "0820c0f2010080bd"))
        assertEquals(1, count(armv7, "0620c0f2010080bd"))
    }
}
