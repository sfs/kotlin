/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util.slicedMap

import com.intellij.openapi.util.Key
import kotlin.math.max

// 64 bits of phi in two's complement
private const val PHI = -0x1E3779B97F4A7C15L
private const val BUCKET_SIZE_LOG2 = 2
private const val MAX_SHIFT = 62

/**
 * Hash an object to an index in a table of size 4 * 2^(64 - shift).
 * Note that since the result is an integer, shift needs to be
 * greater than or equal to 36.
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun Any.toIndex(shift: Int): Int =
    (((hashCode().toLong() * PHI) ushr shift).toInt() shl BUCKET_SIZE_LOG2) + 3

internal class SlicedMapTable {
    private var shift = MAX_SHIFT
    private val capacity: Int
        get() = capacityFor(shift)
    private var size_: Int = 0
    val size get() = size_

    private var indices = IntArray(capacity)
    private var keys = arrayOfNulls<Any>(capacity)
    private var values = arrayOfNulls<Any>(capacity)

    fun <K, V> get(slice: ReadOnlySlice<K, V>, key: K): V? {
        if (key == null) return null

        val sliceIndex = slice.key.hashCode()
        var i = key.toIndex(shift)
        var k = keys[i]
        while (k != null) {
            if (indices[i] == sliceIndex && k == key) {
                @Suppress("UNCHECKED_CAST")
                return values[i] as V?
            }
            if (--i < 0) i = capacity - 1
            k = keys[i]
        }
        return null
    }

    fun <K, V> put(slice: WritableSlice<K, V>, key: K, value: V) {
        if (key == null) return

        val sliceIndex = slice.key.hashCode()
        var i = key.toIndex(shift)
        var k = keys[i]
        while (k != null) {
            if (indices[i] == sliceIndex && k == key) {
                values[i] = value
                return
            }
            if (--i < 0) i = capacity-1
            k = keys[i]
        }

        indices[i] = sliceIndex
        keys[i] = key
        values[i] = value
        size_++

        if (2 * size_ > capacity)
            rehash()
    }

    fun forEach(f: (Int, Any?, Any?) -> Unit) {
        for (i in 0 until capacity)
            if (keys[i] != null)
                f(indices[i], keys[i], values[i])
    }

    private fun rehash() {
        if (shift <= 36)
            throw OutOfMemoryError()
        val newShift = shift - 1
        val newCapacity = capacityFor(newShift)
        val newIndices = IntArray(newCapacity)
        val newKeys = arrayOfNulls<Any>(newCapacity)
        val newValues = arrayOfNulls<Any>(newCapacity)

        for (i in 0 until capacity) {
            val k = keys[i]
            if (k != null) {
                var j = k.toIndex(newShift)
                var kn = newKeys[j]
                while (kn != null) {
                    if (--j < 0)
                        j = newCapacity-1
                    kn = newKeys[j]
                }
                newIndices[j] = indices[i]
                newKeys[j] = k
                newValues[j] = values[i]
            }
        }

        indices = newIndices
        keys = newKeys
        values = newValues
        shift = newShift
    }

    private fun capacityFor(shift: Int) =
        1 shl (BUCKET_SIZE_LOG2 + 64 - shift)
}