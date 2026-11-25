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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.GroovyBasePlugin
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.util.GradleVersion

/**
 * Compiles the Groovy of each source set with {@link IncrementalGroovyCompile}.
 * <p>
 * The {@code GroovyCompile} task of the source set stays, as the place where the compilation is configured, by the build
 * and by other plugins, and as what other tasks depend on. It no longer compiles: it is disabled and depends on the task
 * of this plugin, which reads its configuration and writes to its destination directory.
 */
@CompileStatic
class IncrementalGroovyPlugin implements Plugin<Project> {
    /**
     * The Gradle property that turns the plugin off when {@code false}, and on for a version of Gradle it was not made for
     * when {@code true}.
     */
    public static final String ENABLED_PROPERTY = 'incrementalGroovy.enabled'

    // The compiler is made from Gradle's and uses classes of Gradle that are the same in these versions
    private static final GradleVersion FIRST_SUPPORTED = GradleVersion.version('9.8')
    private static final GradleVersion FIRST_UNSUPPORTED = GradleVersion.version('9.10')

    private static final Logger LOGGER = Logging.getLogger(IncrementalGroovyPlugin)

    @Override
    void apply(Project project) {
        String enabled = project.providers.gradleProperty(ENABLED_PROPERTY).getOrNull()
        if ('false'.equalsIgnoreCase(enabled)) {
            return
        }
        GradleVersion gradleVersion = GradleVersion.current().baseVersion
        boolean supported = gradleVersion >= FIRST_SUPPORTED && gradleVersion < FIRST_UNSUPPORTED
        if (!supported && !'true'.equalsIgnoreCase(enabled)) {
            LOGGER.warn(
                'The incremental-groovy plugin does not support {}, only Gradle {} and later, before {}. Groovy is compiled by Gradle alone. Set the Gradle property {}=true to try the plugin anyway.',
                GradleVersion.current(), FIRST_SUPPORTED.version, FIRST_UNSUPPORTED.version, ENABLED_PROPERTY
            )
            return
        }
        project.plugins.withType(GroovyBasePlugin).configureEach {
            project.extensions.getByType(SourceSetContainer).all { SourceSet sourceSet -> compileGroovyOf(project, sourceSet) }
        }
    }

    private static void compileGroovyOf(Project project, SourceSet sourceSet) {
        TaskProvider<GroovyCompile> gradleTask = project.tasks.named(sourceSet.getCompileTaskName('groovy'), GroovyCompile)
        String abiTaskName = sourceSet.getTaskName('extract', 'groovyCompileClasspathAbi')
        String compileTaskName = sourceSet.getTaskName('compileIncremental', 'groovy')

        TaskProvider<GroovyCompileClasspathAbi> abiTask = project.tasks.register(abiTaskName, GroovyCompileClasspathAbi) { GroovyCompileClasspathAbi task ->
            task.description = "Extracts the ABI of the classpath that the ${sourceSet.name} Groovy source is compiled against."
            task.classpath.from({ gradleTask.get().classpath })
            task.abiFile.set(project.layout.buildDirectory.file("tmp/${abiTaskName}/classpath.abi"))
            task.entryHashes.set(project.layout.buildDirectory.file("tmp/${abiTaskName}/entries.properties"))
        }

        TaskProvider<IncrementalGroovyCompile> compileTask = project.tasks.register(compileTaskName, IncrementalGroovyCompile) { IncrementalGroovyCompile task ->
            GroovyCompile configuredBy = gradleTask.get()
            task.description = "Compiles the ${sourceSet.name} Groovy source, as configured by ${configuredBy.name}."
            task.source.from({ configuredBy.source })
            task.classpath.from({ configuredBy.classpath })
            task.classpathAbi.set(abiTask.flatMap { it.abiFile })
            task.astTransformationClasspath.from(configuredBy.astTransformationClasspath)
            task.groovyClasspath.from({ configuredBy.groovyClasspath })
            task.destinationDirectory.set(project.layout.dir(project.provider { configuredBy.destinationDirectory.get().asFile }))
            task.optionsFrom(configuredBy)
            task.sourceCompatibility.set(project.provider { configuredBy.sourceCompatibility })
            task.targetCompatibility.set(project.provider { configuredBy.targetCompatibility })
            task.javaLauncher.set(configuredBy.javaLauncher)
            task.previousCompilationData.set(project.layout.buildDirectory.file("tmp/${compileTaskName}/previous-compilation-data.bin"))
            task.outputs.doNotCacheIf('Forking compiler via ForkOptions.executable') {
                task.options.fork && task.options.forkOptions.executable != null
            }
        }

        gradleTask.configure { GroovyCompile task ->
            task.enabled = false
            task.dependsOn(compileTask)
        }
    }
}
