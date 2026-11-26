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
package io.github.jdaugherty.incrementalgroovy.benchmark

import groovy.transform.CompileStatic

/**
 * How the generated project compiles Groovy.
 */
@CompileStatic
enum Setup {
    /** Gradle as it compiles Groovy by default, every class of a module whose source or classpath changed */
    GRADLE('gradle', 'Gradle', false, false),
    /** Gradle with {@code options.incremental = true} */
    GRADLE_INCREMENTAL('gradle-incremental', 'Gradle, incremental', true, false),
    /** The plugin, with {@code options.incremental = true} */
    PLUGIN('plugin', 'Plugin', true, true)

    final String id
    final String title
    final boolean incremental
    final boolean plugin

    Setup(String id, String title, boolean incremental, boolean plugin) {
        this.id = id
        this.title = title
        this.incremental = incremental
        this.plugin = plugin
    }

    /**
     * The path of the task that compiles the main Groovy source of a module.
     */
    String compileTask(String module) {
        return plugin ? ":${module}:compileIncrementalGroovy" : ":${module}:compileGroovy"
    }

    static Setup byId(String id) {
        Setup setup = values().find { it.id == id }
        if (setup == null) {
            throw new IllegalArgumentException("Unknown setup '${id}', expected one of ${values()*.id.join(', ')}")
        }
        return setup
    }
}
