package org.dreeam.leaf.util.map;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;

public class StringCanonizingOpenHashMap<T> extends Object2ObjectOpenHashMap<String, T> {

    private static final Interner<String> KEY_INTERNER = Interners.newBuilder().weak().concurrencyLevel(16).build();

    public static String intern(String key) {
        return key != null ? KEY_INTERNER.intern(key) : null;
    }

    public StringCanonizingOpenHashMap() {
        super();
    }

    public StringCanonizingOpenHashMap(int expectedSize) {
        super(expectedSize);
    }

    public StringCanonizingOpenHashMap(int expectedSize, float loadFactor) {
        super(expectedSize, loadFactor);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Map<?, ?> m)) return false;
        if (m.size() != size()) return false;
        if (containsNullKey) {
            if (!value[n].equals(m.get(key[n]))) {
                return false;
            }
        }
        final Object[] key = this.key;
        for (int pos = n; pos-- != 0;) {
            //noinspection ConstantValue
            if (!((key[pos]) == null)) {
                if (!value[pos].equals(m.get(key[pos]))) {
                    return false;
                }
            }
        }
        return true;
    }
}
