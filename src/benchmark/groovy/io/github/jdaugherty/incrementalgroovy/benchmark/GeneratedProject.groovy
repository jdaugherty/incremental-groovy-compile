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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.stream.Stream

/**
 * A project made by {@link ProjectGenerator}.
 */
@CompileStatic
class GeneratedProject {
    final File dir
    /** The modules that compile Groovy, each after the ones it depends on */
    final List<String> modules
    /** The source files the scenarios change, by their role */
    private final Map<String, String> targets

    GeneratedProject(File dir, List<String> modules, Map<String, String> targets) {
        this.dir = dir
        this.modules = modules
        this.targets = targets
    }

    /**
     * The group a module is reported in: the feature modules together, every other module on its own.
     */
    static String groupOf(String module) {
        return module.startsWith('feature') ? 'features' : module
    }

    /**
     * The modules by their group, in the order of the modules.
     */
    static Map<String, List<String>> groups(List<String> modules) {
        Map<String, List<String>> groups = new LinkedHashMap<>()
        for (String module : modules) {
            groups.computeIfAbsent(groupOf(module)) { [] } << module
        }
        return groups
    }

    File target(String role) {
        return new File(dir, targets[role])
    }

    /**
     * The last modified times of the class files of the main source of a module, by their path.
     */
    Map<String, FileTime> classFiles(String module) {
        Path classes = new File(dir, "${module}/build/classes/groovy/main").toPath()
        Map<String, FileTime> classFiles = [:]
        if (!Files.isDirectory(classes)) {
            return classFiles
        }
        Stream<Path> files = Files.walk(classes)
        try {
            files.filter { Path file -> file.toString().endsWith('.class') }.forEach { Path file ->
                classFiles[classes.relativize(file).toString()] = Files.getLastModifiedTime(file)
            }
        } finally {
            files.close()
        }
        return classFiles
    }
}
