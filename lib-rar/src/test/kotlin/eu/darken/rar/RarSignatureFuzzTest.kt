package eu.darken.rar

import com.code_intelligence.jazzer.junit.FuzzTest

/** Seeds live in resources under RarSignatureFuzzTestInputs/signature and replay during `test`. */
class RarSignatureFuzzTest {
    @FuzzTest
    fun signature(data: ByteArray) {
        val kind = RarSignature.detect(data)
        if (kind == RarSignature.Kind.RAR5) check(data.size >= RarSignature.RAR5_SIZE)
        if (kind == RarSignature.Kind.RAR4) check(data.size >= 7 && data[6] == 0.toByte())
    }
}
