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

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * The class files of the classes being recompiled that a class left in the destination directory refers to, directly or
 * through other classes being recompiled, keyed by class name. They have been moved out of the destination directory
 * for the compilation. Empty unless an incremental compilation needs them.
 * <p>
 * A compiler that loads classes of the previous compilation from the destination directory needs them: the JVM resolves the
 * classes a loaded class refers to, whatever the visibility of the reference, and fails with a {@code NoClassDefFoundError}
 * when one of them has been moved out.
 * <p>
 * In Gradle's pull request these are a property of the compile spec. Gradle's compile spec has no such property, so the
 * compile transaction, which stashes the files, hands them to the compiler through this holder, one per task execution.
 */
public final class StashedClassFiles {
    private volatile Map<String, File> classFiles = Collections.emptyMap();

    public Map<String, File> get() {
        return classFiles;
    }

    public void set(Map<String, File> classFiles) {
        this.classFiles = classFiles;
    }
}
