/*
 * Copyright 2026 the original author or authors.
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
 * platforms/jvm/groovy-compiler-worker/src/main/java/org/gradle/api/internal/tasks/compile/DaemonSideCompiler.java
 * It has been moved to another package and modified, see the NOTICE file.
 */
package io.github.jdaugherty.incrementalgroovy.internal.worker;

import org.gradle.api.internal.tasks.compile.GroovyJavaJointCompileSpec;
import org.gradle.api.internal.tasks.compile.JavaCompileSpec;
import org.gradle.api.internal.tasks.compile.JavaHomeBasedJavaCompilerFactory;
import org.gradle.api.internal.tasks.compile.JdkJavaCompiler;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.problems.internal.ProblemsInternal;
import org.gradle.api.tasks.WorkResult;
import org.gradle.language.base.internal.compile.Compiler;

import javax.inject.Inject;
import java.io.File;
import java.util.List;
import java.util.Map;

public class DaemonSideCompiler implements org.gradle.language.base.internal.compile.Compiler<GroovyJavaJointCompileSpec> {

    private final ObjectFactory objectFactory;
    private final List<File> javaCompilerPlugins;
    private final ProblemsInternal problemsService;
    private final Map<String, File> stashedClassFiles;

    /**
     * @param stashedClassFiles the class files of the classes being recompiled that the compiler may have to load, by class name
     */
    @Inject
    public DaemonSideCompiler(ObjectFactory objectFactory, List<File> javaCompilerPlugins, Map<String, File> stashedClassFiles, ProblemsInternal problemsService) {
        this.stashedClassFiles = stashedClassFiles;
        this.objectFactory = objectFactory;
        this.javaCompilerPlugins = javaCompilerPlugins;
        this.problemsService = problemsService;
    }

    @Override
    public WorkResult execute(GroovyJavaJointCompileSpec spec) {
        org.gradle.language.base.internal.compile.Compiler<JavaCompileSpec> javaCompiler = new JdkJavaCompiler(new JavaHomeBasedJavaCompilerFactory(javaCompilerPlugins), problemsService);
        Compiler<GroovyJavaJointCompileSpec> groovyCompiler = new ApiGroovyCompiler(javaCompiler, objectFactory, stashedClassFiles);
        return groovyCompiler.execute(spec);
    }

}
