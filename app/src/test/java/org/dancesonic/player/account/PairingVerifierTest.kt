package org.dancesonic.player.account

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingVerifierTest {
    @Test
    fun `the answer given on screen is returned, and the code is hidden again`() =
        runBlocking<Unit> {
            val shown = mutableListOf<String?>()
            val verifier = PairingVerifier({ shown += it })

            val answer = async { verifier.await("690 125") }
            delay(10)
            verifier.answer(PairingDecision.MATCH)

            assertEquals(PairingDecision.MATCH, answer.await())
            assertEquals(listOf<String?>("690 125", null), shown)
        }

    @Test
    fun `a cancel is passed back as cancel`() =
        runBlocking<Unit> {
            val verifier = PairingVerifier({})

            val answer = async { verifier.await("690 125") }
            delay(10)
            verifier.answer(PairingDecision.CANCEL)

            assertEquals(PairingDecision.CANCEL, answer.await())
        }

    @Test
    fun `no answer before the token expires fails with the expiry message`() {
        val verifier = PairingVerifier({}, waitMillis = 1)

        val error =
            assertThrows(AccountException::class.java) {
                runBlocking { verifier.await("690 125") }
            }
        assertEquals(PairingVerifier.CODE_EXPIRED, error.message)
    }

    @Test
    fun `an answer with no code waiting does nothing`() {
        val verifier = PairingVerifier({})
        verifier.answer(PairingDecision.MATCH)
    }
}
