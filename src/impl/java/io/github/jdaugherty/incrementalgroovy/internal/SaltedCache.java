/*
 * Copyright the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jdaugherty.incrementalgroovy.internal;

import org.gradle.cache.Cache;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Keeps this project's entries of one of Gradle's caches apart from Gradle's own, which have the same keys but hold
 * something else.
 */
final class SaltedCache<V> implements Cache<HashCode, V> {
    private final Cache<HashCode, V> delegate;
    private final String salt;

    SaltedCache(Cache<HashCode, V> delegate, String salt) {
        this.delegate = delegate;
        this.salt = salt;
    }

    @Override
    public V get(HashCode key, Function<? super HashCode, ? extends V> factory) {
        return delegate.get(salted(key), saltedKey -> factory.apply(key));
    }

    @Override
    @Nullable
    public V getIfPresent(HashCode key) {
        return delegate.getIfPresent(salted(key));
    }

    @Override
    public void put(HashCode key, V value) {
        delegate.put(salted(key), value);
    }

    private HashCode salted(HashCode key) {
        Hasher hasher = Hashing.newHasher();
        hasher.putString(salt);
        hasher.putHash(key);
        return hasher.hash();
    }
}
