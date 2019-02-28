/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license 
 * that can be found in the license/LICENSE.txt file.
 */

// Auto-generated file. DO NOT EDIT!

package kotlin

import kotlin.experimental.*

@Suppress("NON_PUBLIC_PRIMARY_CONSTRUCTOR_OF_INLINE_CLASS")
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
public inline class ULong @PublishedApi internal constructor(@PublishedApi internal val data: Long) : Comparable<ULong> {

    companion object {
        /**
         * A constant holding the minimum value an instance of ULong can have.
         */
        public const val MIN_VALUE: ULong = ULong(0)

        /**
         * A constant holding the maximum value an instance of ULong can have.
         */
        public const val MAX_VALUE: ULong = ULong(-1)

        /**
         * The number of bytes used to represent an instance of ULong in a binary form.
         */
        public const val SIZE_BYTES: Int = 8

        /**
         * The number of bits used to represent an instance of ULong in a binary form.
         */
        public const val SIZE_BITS: Int = 64
    }

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: UByte): Int = this.compareTo(other.toULong())

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: UShort): Int = this.compareTo(other.toULong())

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: UInt): Int = this.compareTo(other.toULong())

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    @Suppress("OVERRIDE_BY_INLINE")
    public override inline operator fun compareTo(other: ULong): Int = ulongCompare(this.data, other.data)

    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UByte): ULong = this.plus(other.toULong())
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UShort): ULong = this.plus(other.toULong())
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UInt): ULong = this.plus(other.toULong())
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: ULong): ULong = ULong(this.data.plus(other.data))

    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UByte): ULong = this.minus(other.toULong())
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UShort): ULong = this.minus(other.toULong())
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UInt): ULong = this.minus(other.toULong())
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: ULong): ULong = ULong(this.data.minus(other.data))

    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UByte): ULong = this.times(other.toULong())
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UShort): ULong = this.times(other.toULong())
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UInt): ULong = this.times(other.toULong())
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: ULong): ULong = ULong(this.data.times(other.data))

    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UByte): ULong = this.div(other.toULong())
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UShort): ULong = this.div(other.toULong())
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UInt): ULong = this.div(other.toULong())
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: ULong): ULong = ulongDivide(this, other)

    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UByte): ULong = this.rem(other.toULong())
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UShort): ULong = this.rem(other.toULong())
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UInt): ULong = this.rem(other.toULong())
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: ULong): ULong = ulongRemainder(this, other)

    /** Increments this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun inc(): ULong = ULong(data.inc())
    /** Decrements this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun dec(): ULong = ULong(data.dec())

    /** Creates a range from this value to the specified [other] value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rangeTo(other: ULong): ULongRange = ULongRange(this, other)

    /** Shifts this value left by the [bitCount] number of bits. */
    @kotlin.internal.InlineOnly
    public inline infix fun shl(bitCount: Int): ULong = ULong(data shl bitCount)
    /** Shifts this value right by the [bitCount] number of bits, filling the leftmost bits with zeros. */
    @kotlin.internal.InlineOnly
    public inline infix fun shr(bitCount: Int): ULong = ULong(data ushr bitCount)
    /** Performs a bitwise AND operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun and(other: ULong): ULong = ULong(this.data and other.data)
    /** Performs a bitwise OR operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun or(other: ULong): ULong = ULong(this.data or other.data)
    /** Performs a bitwise XOR operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun xor(other: ULong): ULong = ULong(this.data xor other.data)
    /** Inverts the bits in this value. */
    @kotlin.internal.InlineOnly
    public inline fun inv(): ULong = ULong(data.inv())

    /**
     * Converts this value to [Byte].
     *
     * The resulting `Byte` value is represented by 8 least significant bits of this [ULong] value.
     * Note that the resulting `Byte` value may be negative.
     */
    @kotlin.internal.InlineOnly
    public inline fun toByte(): Byte = data.toByte()
    /**
     * Converts this value to [Short].
     *
     * The resulting `Short` value is represented by 16 least significant bits of this [ULong] value.
     * Note that the resulting `Short` value may be negative.
     */
    @kotlin.internal.InlineOnly
    public inline fun toShort(): Short = data.toShort()
    /**
     * Converts this value to [Int].
     *
     * The resulting `Int` value is represented by 32 least significant bits of this [ULong] value.
     * Note that the resulting `Int` value may be negative.
     */
    @kotlin.internal.InlineOnly
    public inline fun toInt(): Int = data.toInt()
    /**
     * Converts this value to [Long].
     *
     * The resulting `Long` value has the same binary representation as this [ULong] value.
     * Note that the resulting `Long` value is negative if this `ULong` value is greater than [Long.MAX_VALUE].
     */
    @kotlin.internal.InlineOnly
    public inline fun toLong(): Long = data

    /**
     * Converts this value to [UByte].
     *
     * The resulting `UByte` value is represented by 8 least significant bits of this [ULong] value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toUByte(): UByte = data.toUByte()
    /**
     * Converts this value to [UShort].
     *
     * The resulting `UShort` value is represented by 16 least significant bits of this [ULong] value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toUShort(): UShort = data.toUShort()
    /**
     * Converts this value to [UInt].
     *
     * The resulting `UInt` value is represented by 32 least significant bits of this [ULong] value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toUInt(): UInt = data.toUInt()
    /** Returns this value. */
    @kotlin.internal.InlineOnly
    public inline fun toULong(): ULong = this

    /**
     * Converts this value to [Float].
     *
     * The resulting value is the closest `Float` to this [ULong] value.
     * In case when this `ULong` value is exactly between two `Float`s, the smaller one is selected.
     */
    @kotlin.internal.InlineOnly
    public inline fun toFloat(): Float = this.toDouble().toFloat()
    /**
     * Converts this value to [Double].
     *
     * The resulting value is the closest `Double` to this [ULong] value.
     * In case when this `ULong` value is exactly between two `Double`s, the smaller one is selected.
     */
    @kotlin.internal.InlineOnly
    public inline fun toDouble(): Double = ulongToDouble(data)

    public override fun toString(): String = ulongToString(data)

}

/**
 * Converts this value to [ULong].
 *
 * 8 least significant bits of the resulting `ULong` value has the same binary representation as this [Byte] value,
 * whereas 56 most significant bits are filled with sign bit.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Byte.toULong(): ULong = ULong(this.toLong())
/**
 * Converts this value to [ULong].
 *
 * 16 least significant bits of the resulting `ULong` value has the same binary representation as this [Short] value,
 * whereas 48 most significant bits are filled with sign bit.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Short.toULong(): ULong = ULong(this.toLong())
/**
 * Converts this value to [ULong].
 *
 * 32 least significant bits of the resulting `ULong` value has the same binary representation as this [Int] value,
 * whereas 32 most significant bits are filled with sign bit.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Int.toULong(): ULong = ULong(this.toLong())
/**
 * Converts this value to [ULong].
 *
 * The resulting `ULong` value has the same binary representation as this [Long] value.
 * Note that the resulting `ULong` value is greater than [Long.MAX_VALUE] if this `Long` value is negative.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Long.toULong(): ULong = ULong(this)

/**
 * Converts this value to [ULong], rounding down.
 * Returns zero if this [Float] value is negative or NaN, [ULong.MAX_VALUE] if it's bigger than `ULong.MAX_VALUE`.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Float.toULong(): ULong = doubleToULong(this.toDouble())
/**
 * Converts this value to [ULong], rounding down.
 * Returns zero if this [Double] value is negative or NaN, [ULong.MAX_VALUE] if it's bigger than `ULong.MAX_VALUE`.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Double.toULong(): ULong = doubleToULong(this)
