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
package io.github.jdaugherty.incrementalgroovy.internal;

import io.github.jdaugherty.incrementalgroovy.CompilerBackend;
import io.github.jdaugherty.incrementalgroovy.IncrementalGroovyCompile;
import io.github.jdaugherty.incrementalgroovy.internal.daemon.DaemonGroovyCompiler;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.IncrementalCompilerFactory;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.classpath.DefaultClassSetAnalyzer;
import io.github.jdaugherty.incrementalgroovy.internal.incremental.recomp.GroovyRecompilationSpecProvider;
import io.github.jdaugherty.incrementalgroovy.internal.worker.DaemonSideCompiler;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.tasks.compile.AnnotationProcessorDiscoveringCompiler;
import org.gradle.api.internal.TaskOutputsInternal;
import org.gradle.api.internal.tasks.compile.CleaningJavaCompiler;
import org.gradle.api.internal.tasks.compile.CommandLineJavaCompileSpec;
import org.gradle.api.internal.tasks.compile.GroovyJavaJointCompileSpec;
import org.gradle.api.internal.tasks.compile.NormalizingGroovyCompiler;
import org.gradle.api.internal.tasks.compile.daemon.ClassloaderIsolatedCompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.daemon.CompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.daemon.ProcessIsolatedCompilerWorkerExecutor;
import org.gradle.api.internal.tasks.compile.incremental.analyzer.ClassDependenciesAnalyzer;
import org.gradle.api.internal.tasks.compile.incremental.cache.GeneralCompileCaches;
import org.gradle.api.internal.tasks.compile.incremental.classpath.CachingClassSetAnalyzer;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClassSetAnalyzer;
import org.gradle.api.internal.tasks.compile.processing.AnnotationProcessorDetector;
import org.gradle.api.problems.internal.ProblemsInternal;
import org.gradle.api.tasks.WorkResult;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.hash.FileHasher;
import org.gradle.internal.hash.StreamHasher;
import org.gradle.internal.jvm.inspection.JvmVersionDetector;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.worker.child.WorkerDirectoryProvider;
import org.gradle.work.InputChanges;
import org.gradle.workers.internal.ActionExecutionSpecFactory;
import org.gradle.workers.internal.IsolatedClassloaderWorkerFactory;
import org.gradle.workers.internal.WorkerDaemonFactory;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * The compiler of the plugin's tasks: Gradle's Groovy compiler, with this project's incremental compiler and compiler
 * daemon classes in place of Gradle's.
 * <p>
 * Loaded by name by the plugin, see {@code ImplementationLoader}.
 */
public final class GroovyCompilerBackend implements CompilerBackend {
    /**
     * Classpath entries are compared by ABI here and by content in Gradle, so their analyses are cached apart.
     */
    private static final String CLASSPATH_ANALYSIS_SALT = "io.github.jdaugherty.incrementalgroovy:abi:1";

    @Override
    public void compile(IncrementalGroovyCompile task, ServiceRegistry services, InputChanges inputChanges) {
        if (task.getGroovyClasspath().isEmpty()) {
            throw new InvalidUserDataException("The Groovy classpath of " + task.getPath() + " must not be empty. If a Groovy compile dependency is provided, "
                + "the 'groovy-base' plugin will attempt to configure 'groovyClasspath' automatically. Alternatively, you may configure 'groovyClasspath' explicitly.");
        }
        GroovyJavaJointCompileSpec spec = GroovyCompileSpecFactory.createSpec(task, services);
        if (CommandLineJavaCompileSpec.class.isAssignableFrom(spec.getClass())) {
            spec.getCompileOptions().setSupportsIncrementalCompilationAfterFailure(false);
        }
        WorkResult result = createCompiler(task, spec, services, inputChanges).execute(spec);
        task.setDidWork(result.getDidWork());
    }

    private static Compiler<GroovyJavaJointCompileSpec> createCompiler(IncrementalGroovyCompile task, GroovyJavaJointCompileSpec spec, ServiceRegistry services, InputChanges inputChanges) {
        Deleter deleter = services.get(Deleter.class);
        StashedClassFiles stashedClassFiles = new StashedClassFiles();

        // As GroovyCompilerFactory does
        ActionExecutionSpecFactory actionExecutionSpecFactory = services.get(ActionExecutionSpecFactory.class);
        ProjectCacheDir projectCacheDir = services.get(ProjectCacheDir.class);
        CompilerWorkerExecutor compilerWorkerExecutor = spec.getGroovyCompileOptions().isFork()
            ? new ProcessIsolatedCompilerWorkerExecutor(services.get(WorkerDaemonFactory.class), actionExecutionSpecFactory, projectCacheDir)
            : new ClassloaderIsolatedCompilerWorkerExecutor(services.get(IsolatedClassloaderWorkerFactory.class), actionExecutionSpecFactory, projectCacheDir);
        Compiler<GroovyJavaJointCompileSpec> groovyCompiler = new DaemonGroovyCompiler(
            services.get(WorkerDirectoryProvider.class).getWorkingDirectory(),
            services.get(ClassPathRegistry.class),
            compilerWorkerExecutor,
            services.get(ClassLoaderRegistry.class),
            services.get(JavaForkOptionsFactory.class),
            services.get(JvmVersionDetector.class),
            services.get(ProblemsInternal.class),
            stashedClassFiles,
            workerClasses()
        );
        Compiler<GroovyJavaJointCompileSpec> delegatingCompiler = new AnnotationProcessorDiscoveringCompiler<>(new NormalizingGroovyCompiler(groovyCompiler), services.get(AnnotationProcessorDetector.class));

        CleaningJavaCompiler<GroovyJavaJointCompileSpec> cleaningGroovyCompiler = new CleaningJavaCompiler<>(delegatingCompiler, (TaskOutputsInternal) task.getOutputs(), deleter);
        if (!spec.incrementalCompilationEnabled()) {
            return cleaningGroovyCompiler;
        }
        FileCollection stableSources = task.getSource();
        return incrementalCompilerFactory(services).makeIncremental(
            cleaningGroovyCompiler,
            stableSources.getAsFileTree(),
            new GroovyRecompilationSpecProvider(
                deleter,
                services.get(FileOperations.class),
                stableSources.getAsFileTree(),
                inputChanges.isIncremental(),
                () -> inputChanges.getFileChanges(stableSources).iterator(),
                stashedClassFiles
            )
        );
    }

    /**
     * As Gradle's {@code CompileServices} do, with Gradle's analysis of single classes and its caches.
     */
    private static IncrementalCompilerFactory incrementalCompilerFactory(ServiceRegistry services) {
        ClassSetAnalyzer classSetAnalyzer = new CachingClassSetAnalyzer(
            new DefaultClassSetAnalyzer(services.get(FileHasher.class), services.get(StreamHasher.class), services.get(ClassDependenciesAnalyzer.class), services.get(FileOperations.class)),
            services.get(FileSystemAccess.class),
            new SaltedCache<>(services.get(GeneralCompileCaches.class).getClassSetAnalysisCache(), CLASSPATH_ANALYSIS_SALT)
        );
        return new IncrementalCompilerFactory(services.get(BuildOperationExecutor.class), services.get(StringInterner.class), classSetAnalyzer);
    }

    /**
     * The jar or directory that holds the classes of this project that run in the compiler daemon.
     */
    private static URL workerClasses() {
        String resourceName = DaemonSideCompiler.class.getName().replace('.', '/') + ".class";
        URL resource = GroovyCompilerBackend.class.getClassLoader().getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException("Cannot find " + resourceName);
        }
        String url = resource.toExternalForm();
        String root = url.substring(0, url.length() - resourceName.length());
        if (root.startsWith("jar:") && root.endsWith("!/")) {
            root = root.substring("jar:".length(), root.length() - "!/".length());
        }
        try {
            return new URL(root);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Cannot locate the compiler classes from " + url, e);
        }
    }
}
