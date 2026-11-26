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
 * A change to a generated project, followed by a build. Every run of a scenario makes a change of its own, so that each
 * build has something to compile.
 */
@CompileStatic
class Scenario {
    static final List<Scenario> ALL = [
        new Scenario('no-change', 'Nothing changed', null),
        new Scenario('service-body', 'A method body of a service', methodBody('service')),
        new Scenario('entity-body', 'A method body of an entity', methodBody('entity')),
        new Scenario('utility-body', 'A method body of a utility in `core` that many entities call', methodBody('utility')),
        new Scenario('lib-utility-body', 'A method body of a utility in `lib`', methodBody('libUtility')),
        new Scenario('entity-abi', 'A public method added to an entity', publicMethod('entity')),
        new Scenario('shared-entity-abi', 'A public method added to an entity in `core` that most areas use', publicMethod('sharedEntity')),
        // last, as it starts the incremental compilation over
        new Scenario('clean-build', 'Clean build', null, true),
    ]

    final String id
    final String title
    /** Whether the build cleans the project first */
    final boolean clean
    private final Change change

    Scenario(String id, String title, Change change, boolean clean = false) {
        this.id = id
        this.title = title
        this.change = change
        this.clean = clean
    }

    /**
     * @param revision a number that is new for each run
     */
    void apply(GeneratedProject project, int revision) {
        change?.apply(project, revision)
    }

    static Scenario byId(String id) {
        Scenario scenario = ALL.find { it.id == id }
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown scenario '${id}', expected one of ${ALL*.id.join(', ')}")
        }
        return scenario
    }

    /**
     * Changes the string that every method body that a scenario changes holds, {@code 'rev-0'}.
     */
    private static Change methodBody(String role) {
        return { GeneratedProject project, int revision ->
            File file = project.target(role)
            String text = file.getText('UTF-8')
            String changed = text.replaceAll(/'rev-\d+'/, "'rev-${revision}'")
            if (changed == text) {
                throw new IllegalStateException("${file} has no revision to change")
            }
            file.setText(changed, 'UTF-8')
        } as Change
    }

    private static Change publicMethod(String role) {
        return { GeneratedProject project, int revision ->
            File file = project.target(role)
            String text = file.getText('UTF-8')
            int end = text.lastIndexOf('}')
            file.setText(text.substring(0, end) + "\n    String added${revision}() {\n        'added'\n    }\n}\n", 'UTF-8')
        } as Change
    }

    interface Change {
        void apply(GeneratedProject project, int revision)
    }
}
