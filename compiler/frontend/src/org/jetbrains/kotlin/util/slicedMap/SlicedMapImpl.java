/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.util.slicedMap;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.intellij.openapi.util.Key;
import kotlin.jvm.functions.Function3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class SlicedMapImpl implements MutableSlicedMap {

    private final boolean alwaysAllowRewrite;
    @Nullable
    private SlicedMapLog log = null;
    private SlicedMapTable map = null;

    private Multimap<WritableSlice<?, ?>, Object> collectiveSliceKeys = null;

    public SlicedMapImpl(boolean alwaysAllowRewrite) {
        this.alwaysAllowRewrite = alwaysAllowRewrite;
    }

    @Override
    public <K, V> void put(WritableSlice<K, V> slice, K key, V value) {
        if (!slice.check(key, value))
            return;

        RewritePolicy rewritePolicy = slice.getRewritePolicy();
        if (!alwaysAllowRewrite && rewritePolicy.rewriteProcessingNeeded(slice.getKey())) {
            V oldValue = lookup(slice, key);
            if (oldValue != null) {
                //noinspection unchecked
                if (!rewritePolicy.processRewrite(slice, key, oldValue, value)) {
                    return;
                }
            }
        }

        if (slice.isCollective()) {
            if (collectiveSliceKeys == null) {
                collectiveSliceKeys = ArrayListMultimap.create();
            }

            collectiveSliceKeys.put(slice, key);
        }

        if (log != null) {
            if (log.put(slice, key, value)) {
                slice.afterPut(this, key, value);
                return;
            }
            map = new SlicedMapTable();
            log.forEach((index, logKey, logValue) -> {
                //noinspection unchecked
                map.put(sliceFor(index), logKey, logValue);
                return null;
            });
            log = null;
        }

        if (map != null) {
            map.put(slice, key, value);
        } else {
            log = new SlicedMapLog();
            log.put(slice, key, value);
        }

        /*if (map == null)
            map = new SlicedMapTable();
        map.put(slice, key, value);*/

        slice.afterPut(this, key, value);
    }

    private <K, V> V lookup(ReadOnlySlice<K, V> slice, K key) {
        if (log != null) {
            return log.get(slice, key);
        } else if (map != null) {
            return map.get(slice, key);
        } else {
            return null;
        }
    }

    @Override
    public void clear() {
        if (log != null) {
            log.clear();
        } else {
            map = null;
        }
        collectiveSliceKeys = null;
    }

    @Override
    public <K, V> V get(ReadOnlySlice<K, V> slice, K key) {
        V value = lookup(slice, key);
        return slice.computeValue(this, key, value, value == null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Collection<K> getKeys(WritableSlice<K, V> slice) {
        assert slice.isCollective() : "Keys are not collected for slice " + slice;
        if (collectiveSliceKeys == null) return Collections.emptyList();
        return (Collection<K>) collectiveSliceKeys.get(slice);
        /*List<K> keys = new ArrayList<K>();

        forEach((otherSlice, key, value) -> {
            if (otherSlice == slice)
                keys.add((K) key);
            return null;
        });

        return keys;*/
    }

    private WritableSlice sliceFor(int index) {
        Key<?> key = Key.getKeyByIndex(index);
        return ((AbstractWritableSlice) key).getSlice();
    }

    @Override
    public void forEach(@NotNull Function3<WritableSlice, Object, Object, Void> f) {
        if (log != null) {
            log.forEach((index, key, value) -> {
                f.invoke(sliceFor(index), key, value);
                return null;
            });
        } else if (map != null) {
            map.forEach((index, key, value) -> {
                f.invoke(sliceFor(index), key, value);
                return null;
            });
        }
    }

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
}
