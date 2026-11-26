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
import org.gradle.internal.service.ServiceRegistry
import org.gradle.work.InputChanges

/**
 * The compiler of this plugin, as its tasks see it. The implementation is below the {@code internal} package and is loaded
 * by {@link ImplementationLoader}.
 */
@CompileStatic
interface CompilerBackend {
    /**
     * Compiles the sources of the task.
     *
     * @param services the services of the task's project
     * @param inputChanges what changed since the task last ran
     */
    void compile(IncrementalGroovyCompile task, ServiceRegistry services, InputChanges inputChanges)
}
