/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.util.slicedMap;

import com.google.common.collect.ImmutableMap;
import com.intellij.openapi.util.Key;
import kotlin.jvm.functions.Function3;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Implements a mutable map from (slice, key) pairs to values.
 * The map is implemented with a single hash table indexed by keys,
 * where each slot holds multiple values - 4 in the current implementation.
 * We assume that on average each key is associated with less than
 * 4 slices.
 *
 * The hash table itself is implemented using Robin Hood hashing.
 * There are two
 */
public class SlicedMapRH implements MutableSlicedMap {
    // Invariants
    // + hashIndexPairs.size == keyValuePairs.size =: C
    // + C = 1 << (1 + 64 + BUCKET_SIZE_LOG2 - shift)

    // Distance to correct slot:
    // item hashes to index h, ends up in index h'
    // if h' <= h, then the distance is h - h'
    // if h < h', then the item must have wrapped around
    // and the distance is h + capacity - h'
    // that is:
    // distance = h - h' + capacity if < 0
    // distance === h - h' mod capacity

    // now we need to compare two distances.
    // Assume that we have item 1 with hash h in index h' and
    // item 2 with hash u in index h'. Is the distance for 1
    // less than or equal to the distance for 2?
    //
    // if h - h' >= 0 and u - h' >= 0 then this is the case
    // if h - h' <= u - h', i.e., h <= u
    //
    // if h - h' >= 0 and u - h' < 0 then this is the case
    // if h - h' <= u - h' + capacity
    // iff h <= u + capacity
    // now by assumption u < h' and h' <= h
    // this is possible, e.g. capacity = 4,
    // h' = 2, h = 3, u = 0
    // then the distance from u to h' is 2, while the
    // distance from h to h' is 1.
    //
    // if h - h' < 0 and u - h' >= 0 then this is the case
    // if h - h' + capacity <= u - h'
    // iff h + capacity <= u
    //
    // Finally, if h - h' < 0 and u - h' < 0 then this is the case
    // if h <= u
    //
    // h + (h - h' < 0 ? capacity : 0) <= u + (u - u' < 0 ? capacity : 0)
    //
    // Since capacity is a power of two:
    // (h - h') & (capacity - 1) <= (u - h') & (capacity - 1)

    /**
     * Contains key hashCodes (truncated to C) at even positions,
     * sliceIndex + 1 values at odd positions. Slice indices are
     * assigned by a simple counter, which may unfortunately assign index
     * 0
     */
    private final boolean alwaysAllowRewrite;
    private int[] hashIndexPairs = null;
    private Object[] keyValuePairs = null;
    private int shift = MAX_SHIFT;
    private int size = 0;

    public SlicedMapRH(boolean alwaysAllowRewrite) {
        this.alwaysAllowRewrite = alwaysAllowRewrite;
    }

    @Override
    public <K, V> void put(WritableSlice<K, V> slice, K key, V value) {
        if (!slice.check(key, value))
            return;

        RewritePolicy rewritePolicy = slice.getRewritePolicy();
        Key<V> sliceKey = slice.getKey();
        if (!alwaysAllowRewrite && rewritePolicy.rewriteProcessingNeeded(sliceKey)) {
            V oldValue = lookup(slice, key);
            if (oldValue != null && !rewritePolicy.processRewrite(slice, key, oldValue, value))
                return;
        }

        int index = sliceKey.hashCode() + 1;

        if (hashIndexPairs == null) {
            size = 0;
            shift = MAX_SHIFT;
            hashIndexPairs = new int[MIN_CAPACITY];
            keyValuePairs = new Object[MIN_CAPACITY];
            insertNew(hashIndexPairs, keyValuePairs, hashFor(key, index, shift), index, key, value);
            size++;
        } else {
            if (insert(hashIndexPairs, keyValuePairs, hashFor(key, index, shift), index, key, value)) {
                size++;
                if (5 * size > hashIndexPairs.length)
                    rehash(shift - 1);
            }
        }

        slice.afterPut(this, key, value);
    }

    @Override
    public <K, V> V get(ReadOnlySlice<K, V> slice, K key) {
        V value = lookup(slice, key);
        return slice.computeValue(this, key, value, value == null);
    }

    private <K, V> V lookup(ReadOnlySlice<K, V> slice, K key) {
        if (hashIndexPairs == null)
            return null;

        int index = slice.getKey().hashCode() + 1;
        int hash = hashFor(key, index, shift);
        int i = 2 * hash;
        int N = hashIndexPairs.length - 1;
        int distance = 0;
        while (hashIndexPairs[i + 1] != 0 &&
               distance <= ((hashIndexPairs[i] - i/2) & N)) {

            if (hashIndexPairs[i] == hash &&
                hashIndexPairs[i + 1] == index &&
                keyValuePairs[i].equals(key)) {
                //noinspection unchecked
                return (V) keyValuePairs[i + 1];
            }

            i = (i - 2) & N;
            distance++;
            //if (distance > CRITICAL_DISTANCE) {
            //    System.out.println("Boom");
            //}
        }

        return null;
    }

    @Override
    public void clear() {
        hashIndexPairs = null;
        keyValuePairs = null;
    }

    @Override
    public void forEach(@NotNull Function3<WritableSlice, Object, Object, Void> f) {
        if (hashIndexPairs == null)
            return;

        for (int i = 0; i < hashIndexPairs.length; i += 2) {
            Object key = keyValuePairs[i];
            if (key == null)
                continue;

            int index = hashIndexPairs[i+1] - 1;
            Key<?> sliceKey = Key.getKeyByIndex(index);
            //noinspection ConstantConditions
            WritableSlice slice = ((AbstractWritableSlice) sliceKey).getSlice();
            Object value = keyValuePairs[i+1];
            f.invoke(slice, key, value);
        }
    }

    // Should not be a bottleneck.
    @Override
    public <K, V> Collection<K> getKeys(WritableSlice<K, V> slice) {
        List<K> keys = new ArrayList<>();

        forEach((otherSlice, key, value) -> {
            if (otherSlice == slice)
                //noinspection unchecked
                keys.add((K) key);
            return null;
        });

        return keys;
    }

    // Only for debugging
    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> ImmutableMap<K, V> getSliceContents(@NotNull ReadOnlySlice<K, V> slice) {
        ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();

        forEach((otherSlice, key, value) -> {
            if (otherSlice == slice)
                builder.put((K) key, (V) value);
            return null;
        });

        return builder.build();
    }

    private void rehash(int newShift) {
        int capacity = 1 << (33 - newShift);
        int[] newHashIndexPairs = new int[capacity];
        Object[] newKeyValuePairs = new Object[capacity];

        for (int i = 0; i < hashIndexPairs.length; i += 2) {
            if (hashIndexPairs[i+1] == 0)
                continue;

            int index = hashIndexPairs[i+1];
            Object key = keyValuePairs[i];
            Object value = keyValuePairs[i+1];
            insertNew(newHashIndexPairs, newKeyValuePairs, hashFor(key, index, newShift), index, key, value);
        }

        shift = newShift;
        hashIndexPairs = newHashIndexPairs;
        keyValuePairs = newKeyValuePairs;
    }

    private static boolean insert(int[] hashIndexPairs, Object[] keyValuePairs,
            int hash, int index, Object key, Object value
    ) {
        int i = 2 * hash;
        int N = hashIndexPairs.length - 1;
        int distance = 0;
        while (hashIndexPairs[i + 1] != 0) {
            if (distance > ((hashIndexPairs[i] - i/2) & N)) {
                // Distance to current element is less than distance to new element
                // => swap elements, insert a definitely new element
                int oldHash = hashIndexPairs[i];
                int oldIndex = hashIndexPairs[i+1];
                Object oldKey = keyValuePairs[i];
                Object oldValue = keyValuePairs[i+1];

                hashIndexPairs[i] = hash;
                hashIndexPairs[i+1] = index;
                keyValuePairs[i] = key;
                keyValuePairs[i+1] = value;

                insertNew(hashIndexPairs, keyValuePairs, oldHash, oldIndex, oldKey, oldValue);
                return true;
            } else if (hashIndexPairs[i] == hash &&
                       hashIndexPairs[i + 1] == index &&
                       keyValuePairs[i].equals(key)) {
                // duplicate element
                keyValuePairs[i+1] = value;
                return false;
            }

            i = (i - 2) & N;
            distance++;
            //if (distance > CRITICAL_DISTANCE) {
            //    System.out.println("Boom");
            //}
        }

        // Insert a new element
        hashIndexPairs[i] = hash;
        hashIndexPairs[i+1] = index;
        keyValuePairs[i] = key;
        keyValuePairs[i+1] = value;
        return true;
    }

    private static void insertNew(int[] hashIndexPairs, Object[] keyValuePairs,
            int hash, int index, Object key, Object value
    ) {
        int i = 2 * hash;
        int N = hashIndexPairs.length - 1;
        int distance = 0;
        while (hashIndexPairs[i + 1] != 0) {
            int oldDistance = (hashIndexPairs[i] - i/2) & N;
            if (distance > oldDistance) {
                distance = oldDistance;

                int oldHash = hashIndexPairs[i];
                hashIndexPairs[i] = hash;
                hash = oldHash;

                int oldIndex = hashIndexPairs[i+1];
                hashIndexPairs[i+1] = index;
                index = oldIndex;

                Object oldKey = keyValuePairs[i];
                keyValuePairs[i] = key;
                key = oldKey;

                Object oldValue = keyValuePairs[i+1];
                keyValuePairs[i+1] = value;
                value = oldValue;
            }

            i = (i - 2) & N;
            distance++;
            //if (distance > CRITICAL_DISTANCE) {
            //    System.out.println("Boom");
            //}
        }

        hashIndexPairs[i] = hash;
        hashIndexPairs[i+1] = index;
        keyValuePairs[i] = key;
        keyValuePairs[i+1] = value;
    }

    // 64 bits of phi in two's complement encoding

    private static final int MAGIC = (int) 0x9E3779B9L;
    private static final int MAX_SHIFT = 27;
    private static final int MIN_CAPACITY = 1 << (33 - MAX_SHIFT);
    private static final int CRITICAL_DISTANCE = 30;
    //private static final long PHI = -0x1E3779B97F4A7C15L;
    //private static final int MAX_SHIFT = 62;
    //private static final int MIN_CAPACITY = 1 << (65 - MAX_SHIFT);

    private static int hashFor(Object obj1, int obj2, int shift) {
        //int hash = Objects.hashCode(obj1);
        //int hash1 = Objects.hashCode(obj1);
        int hash1 = System.identityHashCode(obj1);
        int mixed1 = b0[hash1 >>> 24] ^ b1[(hash1 >>> 16) & 255] ^ b2[(hash1 >>> 8) & 255] ^ b3[hash1 & 255];
        return mixed1 & ((1 << (32 - shift)) - 1);
        //int hash2 = obj2;
        //int mixed2 = b0[hash2 >>> 24] ^ b1[(hash2 >>> 16) & 255] ^ b2[(hash2 >>> 8) & 255] ^ b3[hash2 & 255];
        //int capacity = 1 << (32 - shift);
        //return (mixed1 ^ mixed2) & (capacity - 1);
        //return (mixed1 ^ mixed2) >>> shift;
        //return ((Objects.hashCode(obj1) * MAGIC) + obj2 * MAGIC) >>> shift;
        //int capacity = 1 << (32 - shift);
        //return Objects.hashCode(obj) & (capacity - 1);
        //return (int) ((obj.hashCode() * PHI) >>> shift);
    }

    private static final int[] b0, b1, b2, b3;

    static {
        Random random = new Random();
        b0 = random.ints(256).toArray();
        b1 = random.ints(256).toArray();
        b2 = random.ints(256).toArray();
        b3 = random.ints(256).toArray();
    }
}
