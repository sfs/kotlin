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
public inline class UByte @PublishedApi internal constructor(@PublishedApi internal val data: Byte) : Comparable<UByte> {

    companion object {
        /**
         * A constant holding the minimum value an instance of UByte can have.
         */
        public const val MIN_VALUE: UByte = UByte(0)

        /**
         * A constant holding the maximum value an instance of UByte can have.
         */
        public const val MAX_VALUE: UByte = UByte(-1)

        /**
         * The number of bytes used to represent an instance of UByte in a binary form.
         */
        public const val SIZE_BYTES: Int = 1

        /**
         * The number of bits used to represent an instance of UByte in a binary form.
         */
        public const val SIZE_BITS: Int = 8
    }

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    @Suppress("OVERRIDE_BY_INLINE")
    public override inline operator fun compareTo(other: UByte): Int = this.toInt().compareTo(other.toInt())

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: UShort): Int = this.toInt().compareTo(other.toInt())

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: UInt): Int = this.toUInt().compareTo(other)

    /**
     * Compares this value with the specified value for order.
     * Returns zero if this value is equal to the specified other value, a negative number if it's less than other,
     * or a positive number if it's greater than other.
     */
    @kotlin.internal.InlineOnly
    public inline operator fun compareTo(other: ULong): Int = this.toULong().compareTo(other)

    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UByte): UInt = this.toUInt().plus(other.toUInt())
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UShort): UInt = this.toUInt().plus(other.toUInt())
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: UInt): UInt = this.toUInt().plus(other)
    /** Adds the other value to this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun plus(other: ULong): ULong = this.toULong().plus(other)

    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UByte): UInt = this.toUInt().minus(other.toUInt())
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UShort): UInt = this.toUInt().minus(other.toUInt())
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: UInt): UInt = this.toUInt().minus(other)
    /** Subtracts the other value from this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun minus(other: ULong): ULong = this.toULong().minus(other)

    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UByte): UInt = this.toUInt().times(other.toUInt())
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UShort): UInt = this.toUInt().times(other.toUInt())
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: UInt): UInt = this.toUInt().times(other)
    /** Multiplies this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun times(other: ULong): ULong = this.toULong().times(other)

    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UByte): UInt = this.toUInt().div(other.toUInt())
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UShort): UInt = this.toUInt().div(other.toUInt())
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: UInt): UInt = this.toUInt().div(other)
    /** Divides this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun div(other: ULong): ULong = this.toULong().div(other)

    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UByte): UInt = this.toUInt().rem(other.toUInt())
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UShort): UInt = this.toUInt().rem(other.toUInt())
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: UInt): UInt = this.toUInt().rem(other)
    /** Calculates the remainder of dividing this value by the other value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rem(other: ULong): ULong = this.toULong().rem(other)

    /** Increments this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun inc(): UByte = UByte(data.inc())
    /** Decrements this value. */
    @kotlin.internal.InlineOnly
    public inline operator fun dec(): UByte = UByte(data.dec())

    /** Creates a range from this value to the specified [other] value. */
    @kotlin.internal.InlineOnly
    public inline operator fun rangeTo(other: UByte): UIntRange = UIntRange(this.toUInt(), other.toUInt())

    /** Performs a bitwise AND operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun and(other: UByte): UByte = UByte(this.data and other.data)
    /** Performs a bitwise OR operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun or(other: UByte): UByte = UByte(this.data or other.data)
    /** Performs a bitwise XOR operation between the two values. */
    @kotlin.internal.InlineOnly
    public inline infix fun xor(other: UByte): UByte = UByte(this.data xor other.data)
    /** Inverts the bits in this value. */
    @kotlin.internal.InlineOnly
    public inline fun inv(): UByte = UByte(data.inv())

    /**
     * Converts this value to Byte.
     * The resulting Byte value has the same binary representation as this value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toByte(): Byte = data
    /**
     * Converts this value to Short.
     * Least significant byte of the resulting Short value has the same binary representation as this value,
     * whereas most significant byte is filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toShort(): Short = data.toShort() and 0xFF
    /**
     * Converts this value to Int.
     * Least significant byte of the resulting Int value has the same binary representation as this value,
     * whereas three most significant bytes are filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toInt(): Int = data.toInt() and 0xFF
    /**
     * Converts this value to Long.
     * Least significant byte of the resulting Long value has the same binary representation as this value,
     * whereas seven most significant bytes are filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toLong(): Long = data.toLong() and 0xFF

    /** Returns this value. */
    @kotlin.internal.InlineOnly
    public inline fun toUByte(): UByte = this
    /**
     * Converts this value to UShort.
     * Least significant byte of the resulting UShort value has the same binary representation as this value,
     * whereas most significant byte is filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toUShort(): UShort = UShort(data.toShort() and 0xFF)
    /**
     * Converts this value to UInt.
     * Least significant byte of the resulting UInt value has the same binary representation as this value,
     * whereas three most significant bytes are filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toUInt(): UInt = UInt(data.toInt() and 0xFF)
    /**
     * Converts this value to ULong.
     * Least significant byte of the resulting ULong value has the same binary representation as this value,
     * whereas seven most significant bytes are filled with zeros.
     */
    @kotlin.internal.InlineOnly
    public inline fun toULong(): ULong = ULong(data.toLong() and 0xFF)

    /**
     * Converts this value to Float.
     * The resulting value is the closest Float to this value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toFloat(): Float = this.toInt().toFloat()
    /**
     * Converts this value to Double.
     * The resulting value is the closest Double to this value.
     */
    @kotlin.internal.InlineOnly
    public inline fun toDouble(): Double = this.toInt().toDouble()

    public override fun toString(): String = toInt().toString()

}

/**
 * Converts this value to UByte.
 * The resulting UByte value has the same binary representation as this value.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Byte.toUByte(): UByte = UByte(this)
/**
 * Converts this value to UByte.
 * The resulting UByte value is represented by least significant byte of this value.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Short.toUByte(): UByte = UByte(this.toByte())
/**
 * Converts this value to UByte.
 * The resulting UByte value is represented by least significant byte of this value.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Int.toUByte(): UByte = UByte(this.toByte())
/**
 * Converts this value to UByte.
 * The resulting UByte value is represented by least significant byte of this value.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Long.toUByte(): UByte = UByte(this.toByte())

/**
 * Converts this value to UByte, rounding toward zero.
 * Returns zero if this value is negative or NaN, UByte.MAX_VALUE if it's bigger than UByte.MAX_VALUE.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Float.toUByte(): UByte = doubleToUByte(this.toDouble())
/**
 * Converts this value to UByte, rounding toward zero.
 * Returns zero if this value is negative or NaN, UByte.MAX_VALUE if it's bigger than UByte.MAX_VALUE.
 */
@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
@kotlin.internal.InlineOnly
public inline fun Double.toUByte(): UByte = doubleToUByte(this)
