package org.reactormonk

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import ru.nsk.kstatemachine.*
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.security.auth.callback.Callback
import kotlin.coroutines.resumeWithException

data class BluetoothDevice(val name: String)
data class CardMessage(val msg: String)
sealed class Command {
    abstract val callback: CancellableContinuation<ParsingState.ParsingSuccessful>
    abstract val commandByte: Byte
    data class AutoCardSearch(override val callback: CancellableContinuation<ParsingState.ParsingSuccessful>): Command() {
        override val commandByte: Byte = 20
    }
    data class PowerOffAntenna(override val callback: CancellableContinuation<ParsingState.ParsingSuccessful>): Command(){
        override val commandByte: Byte = 22
    }
    data class UltralightLongRead(override val callback: CancellableContinuation<ParsingState.ParsingSuccessful>): Command(){
        override val commandByte: Byte = 24
    }
    data class PingCard(override val callback: CancellableContinuation<ParsingState.ParsingSuccessful>): Command(){
        override val commandByte: Byte = 26
    }
}

fun parseSingle(data: ByteArray): ParsingState.ParsingSuccessful {
    return ParsingState.ParsingSuccessful(data, 30)
}

object ParsingState {
    data class ParsingSuccessful(val data: ByteArray, val cmd: Byte) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ParsingSuccessful

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
        fun isSuccessful() = true
    }
}

sealed class States: DefaultState() {
    object ReaderScanning : States()
    object ReaderConnecting : States()
}

data class DeviceFound(val device: BluetoothDevice): Event

sealed interface DkBleManagerEvents: Event {
    data class DeviceFailedToConnect(val device: BluetoothDevice, val reason: Int): DkBleManagerEvents
    data class DeviceReady(val device: BluetoothDevice): DkBleManagerEvents
    data class DeviceDisconnected(val device: BluetoothDevice, val reason: Int): DkBleManagerEvents
}

sealed interface Requests: Event {
    data class VoltageRequest(val continuation: CancellableContinuation<Double>): Requests
}

sealed class ReaderStates: DefaultState() {
    data object CardScanRequested : ReaderStates()
    data object ScanningForCard : ReaderStates()
    data class CardFound(val cardId: ByteArray) : ReaderStates() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CardFound

            return cardId.contentEquals(other.cardId)
        }

        override fun hashCode(): Int {
            return cardId.contentHashCode()
        }
    }

    data class CardOnReader(val card: CardMessage) : ReaderStates()
}

sealed class CardEvents {
    data object CardScanActive: Event
    data class CardFound(override val data: ByteArray): DataEvent<ByteArray> {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CardFound

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }
    data class CardOnReader(override val data: CardMessage): DataEvent<CardMessage>
    data object CardLost: Event
}

data class CardReaderMachine(val stateMachine: StateMachine)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun machine(scope: CoroutineScope): CardReaderMachine {
    lateinit var machine: StateMachine

    val dkServiceId: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")

    // machine.processEvent(DkBleManagerEvents.DeviceReady(device))
    // machine.processEvent(DeviceFound(result.device))

    // no decisions are to be made outside the machine.
    machine = createStateMachine(
        scope,
        name = "DkBle",
        start = false,
    ) {
        logger = StateMachine.Logger { println(it()) }

        ignoredEventHandler = StateMachine.IgnoredEventHandler {
            println( "unexpected event: $it" )
        }

        transition<DkBleManagerEvents.DeviceDisconnected> {
            targetState = States.ReaderScanning
        }

        val readerReady = state(name = "ReaderReady", childMode = ChildMode.PARALLEL) {
            val readerJob = SupervisorJob()
            val readerScope = scope + readerJob
            // TODO figure out how to do this properly
            var prevMsg: CardMessage? = null
            suspend fun send(command: Command) {
                readerScope.launch {
                    machine.processEvent(OutgoingMessage(command))
                }
            }

            onEntry {
                val incomingChannel = Channel<ByteArray>(10)
                readerScope.launch {
                    incomingChannel.consume {
                        consumeAsFlow().collect {
                            machine.processEvent(IncomingBleMessage(it))
                        }
                    }
                }
            }
            onExit {
                readerJob.cancelChildren()
            }

            // TODO factor out the suspendCancellableCoroutine part
            suspend fun requestAutoCardScan(delayMs: Byte, cardType: Byte, enable: Boolean = true): ParsingState.ParsingSuccessful {
                return suspendCancellableCoroutine { continuation ->
                    readerScope.launch {
                        send(Command.AutoCardSearch(continuation))
                    }
                }
            }

            suspend fun longRead(): ParsingState.ParsingSuccessful {
                return suspendCancellableCoroutine { continuation ->
                    readerScope.launch {
                        send(Command.UltralightLongRead(continuation))
                    }
                }
            }

            suspend fun ping(): ParsingState.ParsingSuccessful {
                return suspendCancellableCoroutine { continuation ->
                    readerScope.launch {
                        send(Command.PingCard(continuation))
                    }
                }
            }

            state("ble") {
                addInitialState(CommandStates.Waiting) {
                    transition<IncomingBleMessage> {
                        onTriggered {
                            val value = it.event.data
                            println("Got errant value: $value")
                        }
                    }
                    transitionOn<OutgoingMessage> {
                        targetState = {
                            CommandStates.ExpectingResponse(event, listOf())
                        }
                    }
                    transition<Ping> {
                        onTriggered {
                            println("Errant ping")
                        }
                    }
                    transition<Timeout> {
                        onTriggered {
                            println("Errant timeout")
                        }
                    }
                }

                dataState<CommandStates.ExpectingResponse> {
                    val supervisor = SupervisorJob()
                    onEntry {
                        scope.launch(supervisor) {
                            // TODO make fancier
                            delay(200)
                            machine.processEvent(Ping)
                            delay(200)
                            machine.processEvent(Ping)
                            delay(200)
                            machine.processEvent(Ping)
                            delay(200)
                            machine.processEvent(Timeout)
                        }
                    }

                    val state = this
                    transitionConditionally<IncomingBleMessage> {
                        type = TransitionType.EXTERNAL
                        direction = {
                            val value = event.data
                            val parsed = parseSingle(value)
                            when (val commandByte = (parsed.cmd - 1).toByte()) {
                                state.data.outgoingMessage.data.commandByte -> {
                                    supervisor.cancelChildren()
                                    if (parsed.isSuccessful() || state.data.retryCount > 2) {
                                        state.data.outgoingMessage.data.callback.resume(parsed) {
                                            println("Resumed too late! $commandByte with data: ${value.dump()}")
                                        }
                                        popQueue(state)
                                    } else {
                                        targetState(CommandStates.ExpectingResponse(state.data.outgoingMessage, state.data.queue, state.data.retryCount + 1))
                                    }

                                }

                                else -> {
                                    println("Got errant message: $commandByte with data: ${value.dump()}")
                                    noTransition()
                                }
                            }
                        }
                    }

                    transitionOn<OutgoingMessage> {
                        type = TransitionType.EXTERNAL
                        targetState = {
                            CommandStates.ExpectingResponse(state.data.outgoingMessage, state.data.queue + event)
                        }
                    }
                    transition<Ping> {
                        onTriggered {
                            scope.launch {
                                send(state.data.outgoingMessage.data)
                            }
                        }
                    }
                    transitionConditionally<Timeout> {
                        type = TransitionType.EXTERNAL
                        direction = {
                            state.data.outgoingMessage.data.callback.resumeWithException(TimeoutException("Request timed out."))
                            popQueue(state)
                        }
                    }
                }
            }

            state("card") {
                transition<CardEvents.CardLost> {
                    targetState = ReaderStates.CardScanRequested
                }

                transitionOn<CardEvents.CardFound> {
                    targetState = {
                        ReaderStates.CardFound(event.data)
                    }
                }

                transition<CardEvents.CardScanActive> {
                    targetState = ReaderStates.ScanningForCard
                }

                addInitialState(ReaderStates.CardScanRequested) {
                    onEntry {
                        readerScope.launch {
                            val pong = ping()
                            if (pong.isSuccessful()) {
                                machine.processEvent(CardEvents.CardFound(pong.data.slice(IntRange(1, 8)).toByteArray()))
                            } else {
                                println("Ping unsuccessful, starting scan...")
                                if (requestAutoCardScan(100, 1).isSuccessful()) {
                                    machine.processEvent(CardEvents.CardScanActive)
                                } else {
                                    TODO()
                                }
                            }
                        }
                    }
                }

                dataState<ReaderStates.CardFound> {
                    onEntry {
                        readerScope.launch {
                            val data = longRead()
                            if (data.isSuccessful()) {
                                machine.processEvent(CardEvents.CardOnReader(CardMessage(data.data.dump())))
                            } else {
                                machine.processEvent(CardEvents.CardLost)
                            }
                        }
                    }
                }

                dataState<ReaderStates.CardOnReader> {
                    val state = this
                    val job = SupervisorJob()
                    val cardScope = scope + job
                    onExit {
                        job.cancelChildren()
                    }
                    transitionConditionally<CardEvents.CardFound> {
                        type = TransitionType.EXTERNAL
                        direction = {
                            targetState(ReaderStates.CardFound(event.data))
                        }
                    }
                    transitionConditionally<CardEvents.CardOnReader> {
                        type = TransitionType.EXTERNAL
                        direction = {
                            targetState(ReaderStates.CardOnReader(event.data))
                        }
                    }
                }
            }


        }

        addState(States.ReaderConnecting) {
            transition<DkBleManagerEvents.DeviceReady> {
                targetState = readerReady
            }
        }

        addInitialState(States.ReaderScanning) {
            onEntry {
                scope.launch {
                    delay(100)
                    machine.processEvent(DeviceFound(BluetoothDevice("1")))
                }
            }

            transition<DeviceFound> {
                targetState = States.ReaderConnecting
                onTriggered {
                    machine.processEvent(DkBleManagerEvents.DeviceReady(BluetoothDevice("1")))
                }
            }
        }
    }

    return CardReaderMachine(machine)
}

sealed class CommandStates: DefaultState() {
    data object Waiting : CommandStates()
    data class ExpectingResponse(
        val outgoingMessage: OutgoingMessage,
        val queue: List<OutgoingMessage>,
        val retryCount: Int = 0,
    ): CommandStates()
}

data class IncomingBleMessage(override val data: ByteArray): DataEvent<ByteArray> {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IncomingBleMessage

        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }
}

data class OutgoingMessage(override val data: Command): DataEvent<Command>
data object Ping: Event
data object Timeout: Event

private suspend fun <T : Event> EventAndArgument<T>.popQueue(
    state: DataState<CommandStates.ExpectingResponse>
) = if (state.data.queue.isEmpty()) {
    targetState(CommandStates.Waiting)
} else {
    targetState(
        CommandStates.ExpectingResponse(
            state.data.queue[0],
            state.data.queue.drop(1)
        )
    )
}

fun ByteArray.dump(): String =
    joinToString { String.format("%02X", it) }

fun main() {
    val scope = CoroutineScope(Dispatchers.IO)
    runBlocking {
        val machine = machine(scope)
        machine.stateMachine.start()
        while (!machine.stateMachine.isFinished) {
            delay(1000)
        }
    }
}

