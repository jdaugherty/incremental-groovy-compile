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
import org.gradle.api.tasks.compile.GroovyCompile

/**
 * Loads the compiler of this plugin, the classes below the {@code internal} package.
 * <p>
 * They are made from classes of Gradle and use the libraries that Gradle uses, Guava, ASM and fastutil, as part of the
 * signatures they share with Gradle. A plugin cannot see those libraries, and bringing its own would give it other classes
 * of the same names. So the compiler has a class loader of its own, which sees what Gradle's Groovy compiler sees and the
 * classes of this plugin, and the plugin reaches it through {@link CompilerBackend} only.
 */
@CompileStatic
final class ImplementationLoader {
    private static final String PLUGIN_PACKAGE = 'io.github.jdaugherty.incrementalgroovy.'
    private static final String INTERNAL_PACKAGE = PLUGIN_PACKAGE + 'internal.'
    private static final String BACKEND_CLASS = INTERNAL_PACKAGE + 'GroovyCompilerBackend'
    private static final String WORKER_CLASS = INTERNAL_PACKAGE + 'worker.DaemonSideCompiler'

    private static CompilerBackend backend

    private ImplementationLoader() {
    }

    static synchronized CompilerBackend backend() {
        if (backend == null) {
            Set<URL> classpath = [locationOf(BACKEND_CLASS), locationOf(WORKER_CLASS)] as LinkedHashSet<URL>
            // Lives as long as the plugin's classes do
            ClassLoader loader = new URLClassLoader(classpath as URL[], new GradleAndPluginClassLoader(ImplementationLoader.classLoader))
            try {
                backend = (CompilerBackend) loader.loadClass(BACKEND_CLASS).getConstructor().newInstance()
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException('Cannot load the compiler of the incremental-groovy plugin. This version of Gradle may not be supported.', e)
            }
        }
        return backend
    }

    /**
     * The jar or directory the class is in.
     */
    private static URL locationOf(String className) {
        String resourceName = className.replace('.', '/') + '.class'
        URL resource = ImplementationLoader.classLoader.getResource(resourceName)
        if (resource == null) {
            throw new IllegalStateException("Cannot find ${resourceName}")
        }
        String url = resource.toExternalForm()
        String root = url.substring(0, url.length() - resourceName.length())
        if (root.startsWith('jar:') && root.endsWith('!/')) {
            root = root.substring('jar:'.length(), root.length() - '!/'.length())
        }
        return new URI(root).toURL()
    }

    /**
     * Everything Gradle's Groovy compiler sees, and the classes of this plugin that are not part of its compiler.
     */
    private static final class GradleAndPluginClassLoader extends ClassLoader {
        private final ClassLoader pluginClassLoader

        GradleAndPluginClassLoader(ClassLoader pluginClassLoader) {
            super(GroovyCompile.classLoader)
            this.pluginClassLoader = pluginClassLoader
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.startsWith(PLUGIN_PACKAGE) && !name.startsWith(INTERNAL_PACKAGE)) {
                return pluginClassLoader.loadClass(name)
            }
            throw new ClassNotFoundException(name)
        }
    }
}
