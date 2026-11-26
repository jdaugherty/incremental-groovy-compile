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
 * The size of a generated project.
 */
@CompileStatic
class ProjectShape {
    static final Map<String, ProjectShape> SHAPES = [
        small : new ProjectShape('small', 8, 40, 10),
        medium: new ProjectShape('medium', 25, 40, 20),
        large : new ProjectShape('large', 75, 40, 40),
    ]

    final String name
    /** The groups of entities in {@code core}, each with its own package in {@code core} and in {@code app} */
    final int areas
    final int entitiesPerArea
    /** The utility classes of {@code lib}, besides the ones every project has */
    final int libUtilities

    ProjectShape(String name, int areas, int entitiesPerArea, int libUtilities) {
        this.name = name
        this.areas = areas
        this.entitiesPerArea = entitiesPerArea
        this.libUtilities = libUtilities
    }

    static ProjectShape named(String name) {
        ProjectShape shape = SHAPES[name]
        if (shape == null) {
            throw new IllegalArgumentException("Unknown project size '${name}', expected one of ${SHAPES.keySet().join(', ')}")
        }
        return shape
    }
}
