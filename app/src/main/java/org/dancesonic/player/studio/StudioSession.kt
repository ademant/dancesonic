package org.dancesonic.player.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** The counter each phone keeps per Studio. [advance] stores the new value and returns it, before anything is sent. */
interface StudioCounter {
    suspend fun advance(): Long
}

/** A protocol refusal or a dropped connection, with the wire code (`not_paired`, `replay`, ...). */
class StudioProtocolError(val code: String) : Exception(code)

/**
 * The phone's side of one session with a paired Studio over an open [StudioLink]: `hello`, then signed commands
 * and the state Studio reports. Commands go one at a time, so each refusal, which Studio sends without a counter,
 * belongs to the command that was sent.
 */
class StudioSession
    @Suppress("LongParameterList")
    constructor(
        private val link: StudioLink,
        private val deviceId: String,
        private val signer: StudioSigner,
        private val counter: StudioCounter,
        scope: CoroutineScope,
        private val replyTimeoutMillis: Long = REPLY_TIMEOUT_MILLIS,
        /** Called when Studio reports the addresses it can be reached at on the network. */
        private val onHint: (StudioMessage.NetworkHint) -> Unit = {},
        /**
         * When above 0, a `get_state` goes to Studio this often while the session is open. The relay needs it: Studio
         * ends a relay session that has been silent for 90 seconds (#104).
         */
        keepAliveMillis: Long = 0L,
    ) : AutoCloseable {
        private val _state = MutableStateFlow<StudioPlayState?>(null)

        /** Playback as Studio last reported it; null until the first state arrives. */
        val state: StateFlow<StudioPlayState?> = _state.asStateFlow()

        private val _ended = MutableStateFlow<String?>(null)

        /** Why the session ended (`revoked`, `closed`, ...), or null while it is open. */
        val ended: StateFlow<String?> = _ended.asStateFlow()

        private val commands = Mutex()

        @Volatile private var welcome: CompletableDeferred<StudioMessage.Welcome>? = null

        @Volatile private var pendingResult: CompletableDeferred<StudioMessage.Result>? = null

        @Volatile private var pendingFailure: CompletableDeferred<StudioProtocolError>? = null
        private val reader: Job = scope.launch { readAll() }
        private val keepAlive: Job? =
            if (keepAliveMillis > 0) scope.launch { sendKeepAlives(keepAliveMillis) } else null

        /** Sends `hello` and returns Studio's welcome. Throws [StudioProtocolError] when Studio refuses the phone. */
        suspend fun hello(): StudioMessage.Welcome {
            val answer = CompletableDeferred<StudioMessage.Welcome>()
            welcome = answer
            val next = counter.advance()
            link.send(
                StudioOutgoing.hello(deviceId, next, signer.sign(StudioWire.helloBytes(deviceId, next, link.binding))),
            )
            return withTimeout(replyTimeoutMillis) { answer.await() }
        }

        /**
         * Sends one command and returns Studio's result. A `result` with `ok: false` throws [StudioProtocolError] with
         * the
         * command's error code.
         */
        suspend fun send(command: StudioCommand): StudioMessage.Result =
            commands.withLock {
                val body = command.body()
                val next = counter.advance()
                val result = CompletableDeferred<StudioMessage.Result>()
                val failure = CompletableDeferred<StudioProtocolError>()
                pendingResult = result
                pendingFailure = failure
                try {
                    link.send(
                        StudioOutgoing.command(next, body, signer.sign(StudioWire.commandBytes(deviceId, next, body))),
                    )
                    val answer =
                        withTimeout(replyTimeoutMillis) {
                            select {
                                result.onAwait { it }
                                failure.onAwait { throw it }
                            }
                        }
                    if (!answer.ok) throw StudioProtocolError(answer.error ?: "failed")
                    answer
                } finally {
                    pendingResult = null
                    pendingFailure = null
                }
            }

        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private suspend fun readAll() {
            try {
                for (text in link.incoming) {
                    dispatch(StudioMessage.parse(text) ?: continue)
                }
                end(LINK_LOST)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any failure of the link ends the session; the reason is kept for the UI, not rethrown.
                end(LINK_LOST)
            }
        }

        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private suspend fun sendKeepAlives(everyMillis: Long) {
            while (_ended.value == null) {
                delay(everyMillis)
                try {
                    send(StudioCommand.GetState)
                } catch (e: TimeoutCancellationException) {
                    // A missed keepalive is not an error of its own: a dead link ends the session through the reader.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Likewise for a refusal or a closing link; the session's end is reported through [ended].
                }
            }
        }

        private fun dispatch(message: StudioMessage) {
            when (message) {
                is StudioMessage.State -> _state.value = message.state
                is StudioMessage.NetworkHint -> onHint(message)
                is StudioMessage.Welcome -> welcome?.complete(message)
                is StudioMessage.Result -> pendingResult?.complete(message)
                is StudioMessage.Error -> {
                    val code = message.code
                    welcome?.completeExceptionally(StudioProtocolError(code))
                    pendingFailure?.complete(StudioProtocolError(code))
                }
                is StudioMessage.Bye -> {
                    end(message.reason)
                    link.close()
                }
                else -> Unit
            }
        }

        private fun end(reason: String) {
            if (_ended.value == null) _ended.value = reason
            welcome?.completeExceptionally(StudioProtocolError(reason))
            pendingFailure?.complete(StudioProtocolError(reason))
        }

        override fun close() {
            keepAlive?.cancel()
            reader.cancel()
            link.close()
            end("closed")
        }

        companion object {
            /** How long Studio may take to answer a command or a hello. */
            const val REPLY_TIMEOUT_MILLIS = 10_000L

            /**
             * Why [ended] holds this value: the link dropped. The phone may open the other transport and carry on.
             * Other
             * reasons (`closed` by the phone, or Studio's `bye`, such as `revoked`) do not allow that.
             */
            const val LINK_LOST = "link_lost"
        }
    }
