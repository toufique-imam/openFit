package com.openfit.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** java.time and range types as strings or numbers, for the saved screen snapshots ([com.openfit.data.SnapshotStore]). */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

object DurationSerializer : KSerializer<Duration> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Duration", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.toMillis())
    override fun deserialize(decoder: Decoder): Duration = Duration.ofMillis(decoder.decodeLong())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntRange", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: IntRange) = encoder.encodeString("${value.first}..${value.last}")
    override fun deserialize(decoder: Decoder): IntRange = decoder.decodeString().split("..").let { (first, last) -> first.toInt()..last.toInt() }
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

/** A double range (a chart's band) as "low..high". */
object DoubleRangeSerializer : KSerializer<ClosedFloatingPointRange<Double>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DoubleRange", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ClosedFloatingPointRange<Double>) = encoder.encodeString("${value.start}..${value.endInclusive}")
    override fun deserialize(decoder: Decoder): ClosedFloatingPointRange<Double> =
        decoder.decodeString().split("..").let { (low, high) -> low.toDouble()..high.toDouble() }
}
