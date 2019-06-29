/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util.slicedMap

import com.intellij.openapi.util.Key

const val LOG_CAPACITY = 32

internal class SlicedMapLog {
    private val indices = IntArray(LOG_CAPACITY)
    private val keys = arrayOfNulls<Any>(LOG_CAPACITY)
    private val values = arrayOfNulls<Any>(LOG_CAPACITY)
    private var size_ = 0

    val size: Int
        get() = size_

    fun clear() {
        keys.fill(null, 0, size_)
        values.fill(null, 0, size_)
        size_ = 0
    }

    fun <K,V> get(slice: ReadOnlySlice<K, V>, key: K): V? {
        val index = slice.key.hashCode()
        for (i in 0 until size_)
            if (indices[i] == index && keys[i] == key)
                @Suppress("UNCHECKED_CAST")
                return values[i] as V?
        return null
    }

    fun <K, V> put(slice: WritableSlice<K, V>, key: K, value: V): Boolean {
        val index = slice.key.hashCode()
        for (i in 0 until size_) {
            if (indices[i] == index && keys[i] == key) {
                values[i] = value
                return true
            }
        }

        if (size_ == LOG_CAPACITY)
            return false

        indices[size_] = index
        keys[size_] = key
        values[size_] = value
        size_++
        return true
    }

    inline fun forEach(f: (Int, Any?, Any?) -> Unit) {
        for (i in 0 until size)
            f(indices[i], keys[i], values[i])
    }
}