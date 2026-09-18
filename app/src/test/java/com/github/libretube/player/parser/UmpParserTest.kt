package com.github.libretube.player.parser

import org.junit.Test
import org.junit.Assert.*

/**
 * Classe locale UmpPartId pour les tests unitaires
 * (Le vrai package video_streaming. UmpPartId est généré par protobuf au build)
 */
object UmpPartId {
    enum class UMPPartId(val value: Int) {
        MEDIA_HEADER(20)
    }
}

/**
 * Classe de données pour représenter une partie UMP
 */
data class UmpPart(
    val type: UmpPartId.UMPPartId,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UmpPart

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

class ParserTest {

    @Test
    fun testReadVarint() {
        val testCases = listOf(
            // 1 byte long varint
            Pair(byteArrayOf(0x01), 1u),
            Pair(byteArrayOf(0x4F), 79u),
            // 2 byte long varint
            Pair(byteArrayOf(0x96.toByte(), 0), 22u),
            Pair(byteArrayOf(0x80.toByte(), 0x01), 64u),
            Pair(byteArrayOf(0x8A.toByte(), 0x7F), 8138u),
            Pair(byteArrayOf(0xBF.toByte(), 0x7F), 8191u),
            // 3 byte long varint
            Pair(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 0x01), 12288u),
            Pair(byteArrayOf(0xDF.toByte(), 0x7F, 0xFF.toByte()), 2093055u),
            // 4 byte long varint
            Pair(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01), 1574912u),
            Pair(byteArrayOf(0xEF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte()), 268433407u),
            // 5 byte long varint
            Pair(byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01), 25198720u),
            Pair(byteArrayOf(0xFF.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 4294967167u)
        )

        for ((data, expected) in testCases) {
            val parser = UmpParser(data)
            val result = parser.readVarint()

            // Vérifier que le résultat n'est pas null avant de le comparer
            assertNotNull("Result should not be null for input: ${data.joinToString { it.toString() }}", result)

            assertEquals(
                "Failed for input: ${data.joinToString { it.toString() }}",
                expected,
                result!!
            )
        }
    }

    @Test
    fun testReadPart() {
        val parser = UmpParser(byteArrayOf(20, 1, 42))
        val part = parser.readPart()

        assertNotNull(part)

        // ✅ Comparaison par le nom de l'enum pour éviter le warning de types inconvertibles
        // Le premier byte (20) correspond à MEDIA_HEADER dans l'enum
        assertEquals(UmpPartId.UMPPartId.MEDIA_HEADER.name, part?.type?.name)

        assertArrayEquals(byteArrayOf(42), part?.data)
        assertTrue(parser.data().isEmpty())
    }
}