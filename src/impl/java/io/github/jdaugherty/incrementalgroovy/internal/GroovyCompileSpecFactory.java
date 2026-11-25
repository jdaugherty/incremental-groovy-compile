/*
 * Copyright 2009 the original author or authors.
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

/*
 * This file is derived from Gradle (https://github.com/gradle/gradle), from
 * platforms/jvm/language-groovy/src/main/java/org/gradle/api/tasks/compile/GroovyCompile.java
 * It has been moved to another package and modified, see the NOTICE file.
 */
package io.github.jdaugherty.incrementalgroovy.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.github.jdaugherty.incrementalgroovy.IncrementalGroovyCompile;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.JavaVersion;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.tasks.compile.CompilationSourceDirs;
import org.gradle.api.internal.tasks.compile.DefaultGroovyJavaJointCompileSpec;
import org.gradle.api.internal.tasks.compile.DefaultGroovyJavaJointCompileSpecFactory;
import org.gradle.api.internal.tasks.compile.GroovyJavaJointCompileSpec;
import org.gradle.api.internal.tasks.compile.MinimalGroovyCompileOptions;
import org.gradle.api.tasks.compile.CompileOptions;
import org.gradle.internal.buildoption.FeatureFlags;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;
import org.gradle.util.internal.GFileUtils;

import java.io.File;
import java.util.List;

/**
 * Makes the compile spec of an {@link IncrementalGroovyCompile} task, as {@code GroovyCompile} makes its own.
 */
final class GroovyCompileSpecFactory {
    private GroovyCompileSpecFactory() {
    }

    static boolean compilationAvoidanceEnabled(ServiceRegistry services) {
        return services.get(FeatureFlags.class).isEnabled(FeaturePreviews.Feature.GROOVY_COMPILATION_AVOIDANCE);
    }

    static GroovyJavaJointCompileSpec createSpec(IncrementalGroovyCompile task, ServiceRegistry services) {
        CompileOptions options = task.getOptions();
        JavaInstallationMetadata toolchain = task.getJavaLauncher().get().getMetadata();
        DefaultGroovyJavaJointCompileSpec spec = new DefaultGroovyJavaJointCompileSpecFactory(options, toolchain).create();
        assert spec != null;

        FileTreeInternal stableSourcesAsFileTree = (FileTreeInternal) task.getSource().getAsFileTree();
        List<File> sourceRoots = CompilationSourceDirs.inferSourceRoots(stableSourcesAsFileTree);

        spec.setSourcesRoots(sourceRoots);
        spec.setSourceFiles(stableSourcesAsFileTree);
        spec.setDestinationDir(task.getDestinationDirectory().getAsFile().get());
        spec.setWorkingDir(task.getProjectLayout().getProjectDirectory().getAsFile());
        spec.setTempDir(task.getTemporaryDir());
        spec.setCompileClasspath(ImmutableList.copyOf(determineGroovyCompileClasspath(task, services)));
        configureCompatibilityOptions(task, toolchain, spec);
        spec.setAnnotationProcessorPath(Lists.newArrayList(options.getAnnotationProcessorPath() == null ? task.getProjectLayout().files() : options.getAnnotationProcessorPath()));
        spec.setGroovyClasspath(Lists.newArrayList(task.getGroovyClasspath()));
        spec.setCompileOptions(options);
        spec.setGroovyCompileOptions(new MinimalGroovyCompileOptions(task.getGroovyOptions()));
        spec.getCompileOptions().setSupportsCompilerApi(true);
        if (options.isIncremental()) {
            validateIncrementalCompilationOptions(sourceRoots, spec.annotationProcessingConfigured());
            spec.getCompileOptions().setPreviousCompilationDataFile(task.getPreviousCompilationData().getAsFile().get());
        }
        if (spec.getGroovyCompileOptions().getStubDir() == null) {
            File dir = new File(task.getTemporaryDir(), "groovy-java-stubs");
            GFileUtils.mkdirs(dir);
            spec.getGroovyCompileOptions().setStubDir(dir);
        }

        String executable = task.getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath();
        spec.getCompileOptions().getForkOptions().setExecutable(executable);

        return spec;
    }

    private static FileCollection determineGroovyCompileClasspath(IncrementalGroovyCompile task, ServiceRegistry services) {
        if (compilationAvoidanceEnabled(services)) {
            return task.getAstTransformationClasspath().plus(task.getClasspath());
        } else {
            return task.getClasspath();
        }
    }

    private static void validateIncrementalCompilationOptions(List<File> sourceRoots, boolean annotationProcessingConfigured) {
        if (sourceRoots.isEmpty()) {
            throw new InvalidUserDataException("Unable to infer source roots. Incremental Groovy compilation requires the source roots. Change the configuration of your sources or disable incremental Groovy compilation.");
        }

        if (annotationProcessingConfigured) {
            throw new InvalidUserDataException("Enabling incremental compilation and configuring Java annotation processors for Groovy compilation is not allowed. Disable incremental Groovy compilation or remove the Java annotation processor configuration.");
        }
    }

    private static void configureCompatibilityOptions(IncrementalGroovyCompile task, JavaInstallationMetadata toolchain, DefaultGroovyJavaJointCompileSpec spec) {
        String toolchainVersion = JavaVersion.toVersion(toolchain.getLanguageVersion().asInt()).toString();
        String sourceCompatibility = task.getSourceCompatibility().getOrNull();
        // Compatibility can be null if no convention was configured, e.g. when JavaBasePlugin is not applied
        if (sourceCompatibility == null) {
            sourceCompatibility = toolchainVersion;
        }
        String targetCompatibility = task.getTargetCompatibility().getOrNull();
        if (targetCompatibility == null) {
            targetCompatibility = sourceCompatibility;
        }

        spec.setSourceCompatibility(sourceCompatibility);
        spec.setTargetCompatibility(targetCompatibility);
    }
}
