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
 * One run of a scenario: the change, and the builds it took until the project compiled.
 */
@CompileStatic
class Measurement {
    final Setup setup
    final Scenario scenario
    /** Counted from 0, warm-up runs included */
    final int run
    final boolean warmUp
    /** The time of all the builds of the run */
    final long millis
    /** Why the builds that failed failed, in order. The last build of a run always succeeds. */
    final List<String> failures
    final Map<String, ModuleResult> modules

    Measurement(Setup setup, Scenario scenario, int run, boolean warmUp, long millis, List<String> failures, Map<String, ModuleResult> modules) {
        this.setup = setup
        this.scenario = scenario
        this.run = run
        this.warmUp = warmUp
        this.millis = millis
        this.failures = failures
        this.modules = modules
    }

    int getBuilds() {
        return failures.size() + 1
    }

    int getRecompiledClasses() {
        return (int) modules.values().sum(0) { ModuleResult module -> module.recompiledClasses }
    }

    /**
     * What the compilation of a module did in a run.
     */
    static class ModuleResult {
        /** The outcome of the compile task in the last build */
        final String outcome
        /** The time of the compile task in all the builds */
        final long compileMillis
        /** The time of the plugin's task that extracts the ABI of the compile classpath in all the builds */
        final long classpathAbiMillis
        /** The class files that were written */
        final int recompiledClasses
        /** Whether the compile task failed in a build */
        final boolean failed

        ModuleResult(String outcome, long compileMillis, long classpathAbiMillis, int recompiledClasses, boolean failed) {
            this.outcome = outcome
            this.compileMillis = compileMillis
            this.classpathAbiMillis = classpathAbiMillis
            this.recompiledClasses = recompiledClasses
            this.failed = failed
        }
    }
}
