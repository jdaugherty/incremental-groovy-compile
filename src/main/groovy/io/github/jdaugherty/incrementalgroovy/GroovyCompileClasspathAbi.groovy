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
package io.github.jdaugherty.incrementalgroovy

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges

/**
 * Writes down the ABI of a compile classpath, one hash for each of its entries, in the order of the classpath.
 * <p>
 * {@link IncrementalGroovyCompile} takes that file as its input in place of the classpath, so it runs for a change to a
 * dependency only when something it can see of the dependency changed.
 */
@CompileStatic
@CacheableTask
abstract class GroovyCompileClasspathAbi extends DefaultTask {
    @Classpath
    @Incremental
    abstract ConfigurableFileCollection getClasspath()

    @OutputFile
    abstract RegularFileProperty getAbiFile()

    /**
     * The hash of each entry by its path, kept from one run to the next for the entries that did not change.
     */
    @LocalState
    abstract RegularFileProperty getEntryHashes()

    @TaskAction
    void write(InputChanges inputChanges) {
        File stateFile = entryHashes.get().asFile
        Properties known = new Properties()
        if (inputChanges.incremental && stateFile.file) {
            stateFile.withInputStream { known.load(it) }
            for (FileChange change : inputChanges.getFileChanges(classpath)) {
                forget(known, change.file)
            }
        }

        CompilerBackend backend = ImplementationLoader.backend()
        Properties current = new Properties()
        StringBuilder abi = new StringBuilder()
        for (File entry : classpath.files) {
            String hash = known.getProperty(entry.absolutePath)
            if (hash == null) {
                hash = entry.exists() ? backend.abiOf(entry) : 'missing'
            }
            current.setProperty(entry.absolutePath, hash)
            abi.append(hash).append('\n')
        }

        stateFile.parentFile.mkdirs()
        stateFile.withOutputStream { current.store(it, null) }
        abiFile.get().asFile.setText(abi.toString(), 'UTF-8')
    }

    /**
     * Forgets the entry that is the changed file, or the class directory that holds it.
     */
    private static void forget(Properties known, File changed) {
        String path = changed.absolutePath
        known.keySet().removeAll { Object entry -> path == entry || path.startsWith((String) entry + File.separator) }
    }
}
