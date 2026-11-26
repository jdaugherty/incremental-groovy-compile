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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.CompileOptions
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.GroovyCompileOptions
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.work.Incremental
import org.gradle.work.InputChanges

import javax.inject.Inject

/**
 * Compiles Groovy, and Java together with it, incrementally when {@code options.incremental} is on.
 * <p>
 * The plugin adds one of these for each source set with Groovy sources, to do the work of the source set's
 * {@code GroovyCompile} task. It is configured through that task: both have the same options, sources and classpaths.
 */
@CompileStatic
@CacheableTask
abstract class IncrementalGroovyCompile extends DefaultTask {
    private final CompileOptions options
    private final GroovyCompileOptions groovyOptions
    // not kept in the configuration cache, which holds the options as they are once copied
    private transient GroovyCompile configuredBy

    IncrementalGroovyCompile() {
        options = objectFactory.newInstance(CompileOptions)
        groovyOptions = objectFactory.newInstance(GroovyCompileOptions)
    }

    /**
     * Makes the options of this task those of the given task, as they are when Gradle first asks for them, which is
     * once the build is configured.
     */
    void optionsFrom(GroovyCompile configuredBy) {
        this.configuredBy = configuredBy
    }

    private void copyOptions() {
        if (configuredBy != null) {
            GroovyCompile from = configuredBy
            configuredBy = null
            CompileOptionsCopier copier = new CompileOptionsCopier(providerFactory)
            copier.copy(CompileOptions, from.options, options)
            copier.copy(GroovyCompileOptions, from.groovyOptions, groovyOptions)
        }
    }

    /**
     * The Groovy and Java sources to compile.
     */
    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSource()

    /**
     * The classpath to compile against.
     * <p>
     * Gradle compares it by its ABI to tell whether the task is up-to-date. Where Gradle sees a change that the ABI the
     * compiler compares classes by does not, the task runs and compiles nothing.
     */
    @CompileClasspath
    @Incremental
    abstract ConfigurableFileCollection getClasspath()

    /**
     * The classpath containing AST transformations and their dependencies.
     */
    @Classpath
    abstract ConfigurableFileCollection getAstTransformationClasspath()

    /**
     * The classpath containing the version of Groovy to compile with.
     */
    @Classpath
    abstract ConfigurableFileCollection getGroovyClasspath()

    @OutputDirectory
    abstract DirectoryProperty getDestinationDirectory()

    /**
     * The options of the Java compilation.
     */
    @Nested
    CompileOptions getOptions() {
        copyOptions()
        return options
    }

    /**
     * The options of the Groovy compilation.
     */
    @Nested
    GroovyCompileOptions getGroovyOptions() {
        copyOptions()
        return groovyOptions
    }

    /**
     * The Java version of the sources. That of the toolchain when absent.
     */
    @Input
    @Optional
    abstract Property<String> getSourceCompatibility()

    /**
     * The Java version to compile for. {@link #getSourceCompatibility()} when absent.
     */
    @Input
    @Optional
    abstract Property<String> getTargetCompatibility()

    /**
     * The toolchain to run the compiler with.
     */
    @Nested
    abstract Property<JavaLauncher> getJavaLauncher()

    /**
     * The Groovy compiler produces different results depending on the JVM it runs on.
     */
    @Input
    String getGroovyCompilerJvmVersion() {
        return javaLauncher.get().metadata.languageVersion.toString()
    }

    /**
     * What the task knows about its previous compilation.
     */
    @OutputFile
    abstract RegularFileProperty getPreviousCompilationData()

    @Inject
    abstract ProjectLayout getProjectLayout()

    @Inject
    protected abstract ObjectFactory getObjectFactory()

    @Inject
    protected abstract ProviderFactory getProviderFactory()

    @TaskAction
    void compile(InputChanges inputChanges) {
        ImplementationLoader.backend().compile(this, services, inputChanges)
    }
}
