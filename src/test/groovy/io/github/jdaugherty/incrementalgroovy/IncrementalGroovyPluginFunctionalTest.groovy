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

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.attribute.FileTime

/**
 * Builds a project of two modules, {@code api} and {@code impl}, where {@code impl} compiles Groovy incrementally against
 * the jar of {@code api}, with and without the plugin.
 */
class IncrementalGroovyPluginFunctionalTest extends Specification {
    static final List<String> GRADLE_VERSIONS = System.getProperty('testedGradleVersions', '').split(',').findAll()

    @TempDir
    File projectDir

    Map<String, FileTime> snapshot

    def setup() {
        file('settings.gradle') << """
            rootProject.name = 'sample'
            include 'api', 'impl'
        """
        // global transformations are found through META-INF/services, which only the jar carries
        file('gradle.properties') << 'systemProp.org.gradle.java.compile-classpath-packaging=true\n'
        file('api/build.gradle') << """
            plugins { id 'groovy' }
            dependencies { implementation localGroovy() }
        """
        implBuildFile(true)
    }

    void implBuildFile(boolean withPlugin) {
        file('impl/build.gradle').text = """
            plugins {
                id 'groovy'
                ${withPlugin ? "id 'io.github.jdaugherty.incremental-groovy'" : ''}
            }
            dependencies {
                implementation localGroovy()
                implementation project(':api')
            }
            tasks.withType(GroovyCompile).configureEach {
                options.incremental = true
            }
        """
    }

    File file(String path) {
        File file = new File(projectDir, path)
        file.parentFile.mkdirs()
        return file
    }

    File source(String project, String body) {
        String name = (body =~ /(?:class|trait|interface)\s+(\w+)/)[0][1]
        File file = file("$project/src/main/groovy/${name}.groovy")
        file.text = body
        return file
    }

    GradleRunner runner(String gradleVersion, String... arguments) {
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withGradleVersion(gradleVersion)
            .withArguments(arguments.toList() + ['--stacktrace'])
    }

    BuildResult compile(String gradleVersion, String... arguments) {
        return runner(gradleVersion, ([':impl:compileGroovy'] + arguments.toList()) as String[]).build()
    }

    Map<String, FileTime> classFiles() {
        File classesDir = file('impl/build/classes/groovy/main')
        Map<String, FileTime> classFiles = [:]
        classesDir.eachFileRecurse { File file ->
            if (file.name.endsWith('.class')) {
                classFiles[classesDir.toPath().relativize(file.toPath()).toString() - '.class'] = Files.getLastModifiedTime(file.toPath())
            }
        }
        return classFiles
    }

    void takeSnapshot() {
        snapshot = classFiles()
    }

    Set<String> recompiledClasses() {
        return classFiles().findAll { name, time -> snapshot[name] != time }.keySet()
    }

    /**
     * A global transformation that loads the classes of the previous output it meets as field types, like the
     * transformations of Grails' GORM do for domain classes.
     */
    void classLoadingTransformation() {
        file('api/src/main/groovy/InspectFieldTypes.groovy') << '''
            import org.codehaus.groovy.ast.ASTNode
            import org.codehaus.groovy.ast.ClassNode
            import org.codehaus.groovy.ast.FieldNode
            import org.codehaus.groovy.control.CompilePhase
            import org.codehaus.groovy.control.SourceUnit
            import org.codehaus.groovy.transform.ASTTransformation
            import org.codehaus.groovy.transform.GroovyASTTransformation

            @GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
            class InspectFieldTypes implements ASTTransformation {
                void visit(ASTNode[] nodes, SourceUnit source) {
                    for (ClassNode classNode : source.AST.classes) {
                        for (FieldNode field : classNode.fields) {
                            ClassNode type = field.type
                            if (!type.isPrimaryClassNode() && type.isResolved() && !type.name.contains('.')) {
                                type.typeClass.methods
                                if (!type.typeClass.primitive) {
                                    getClass().classLoader.loadClass(type.name).methods
                                }
                            }
                        }
                    }
                }
            }
        '''
        file('api/src/main/resources/META-INF/services/org.codehaus.groovy.transform.ASTTransformation') << 'InspectFieldTypes\n'
    }

    /**
     * Base uses Util in a method body only, so a change to Util recompiles Base. Holder is loaded by the transformation
     * while Base is compiled; it refers to Child, whose superclass Base is out of the output meanwhile.
     */
    File staleClassWhoseSuperclassIsRecompiled() {
        classLoadingTransformation()
        File util = source('impl', "class Util { static String zone(String id) { id?.trim() } }")
        source('impl', "class Base { Holder holder; String zone() { Util.zone('UTC') } }")
        source('impl', "class Child extends Base {}")
        source('impl', "class Holder { Child child }")
        return util
    }

    def "Gradle #gradleVersion alone fails when a transformation loads a stale class whose superclass is recompiled"() {
        given:
        implBuildFile(false)
        File util = staleClassWhoseSuperclassIsRecompiled()
        compile(gradleVersion)

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        BuildResult result = runner(gradleVersion, ':impl:compileGroovy').buildAndFail()

        then:
        result.output.contains('NoClassDefFoundError')

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "does not fail when a transformation loads a stale class whose superclass is recompiled on Gradle #gradleVersion"() {
        given:
        File util = staleClassWhoseSuperclassIsRecompiled()
        compile(gradleVersion)
        takeSnapshot()

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        compile(gradleVersion)

        then:
        recompiledClasses() == ['Util', 'Base'] as Set

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "does not fail when a precompiled trait helper refers to a recompiled class only privately on Gradle #gradleVersion"() {
        given:
        // ZoneError and A are private dependents of Util. The helper of WithZone refers to ZoneError in a private method only,
        // so it is not recompiled, and is loaded from the previous output while ZoneError is being recompiled
        File util = source('impl', "class Util { static String zone(String id) { id?.trim() } }")
        source('impl', "class ZoneError extends RuntimeException { String zone() { Util.zone('UTC') } }")
        source('impl', "trait WithZone { private ZoneError error() { new ZoneError() }; String describe() { error().message } }")
        source('impl', "class A implements WithZone { String zone() { Util.zone('UTC') } }")
        compile(gradleVersion)
        takeSnapshot()

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        compile(gradleVersion)

        then:
        recompiledClasses() == ['Util', 'ZoneError', 'A'] as Set

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "recompiles all dependents of a recompiled class with a Java source on Gradle #gradleVersion"() {
        given:
        // J is recompiled as a dependent of Util, and is out of the output until the Java compiler has run.
        // User refers to J privately, and the Groovy compiler cannot read User without J
        File util = source('impl', "class Util { static String zone(String id) { id?.trim() } }")
        file('impl/src/main/groovy/J.java').text = "public class J { public String zone() { return Util.zone(\"UTC\"); } }"
        source('impl', "class User { private J j }")
        source('impl', "class Other { User user; String zone() { Util.zone('UTC') } }")
        compile(gradleVersion)
        takeSnapshot()

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        compile(gradleVersion)

        then:
        recompiledClasses() == ['Util', 'J', 'User', 'Other'] as Set

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "does not recompile the dependents of a classpath class whose ABI is unchanged on Gradle #gradleVersion"() {
        given:
        // without it the compile classpath is the classpath of the transformations too, and any change to it means a full recompilation
        file('settings.gradle') << "enableFeaturePreview('GROOVY_COMPILATION_AVOIDANCE')\n"
        File a = source('api', "class A { void foo() { } }")
        File b = source('api', "class B { void bar() { } }")
        source('impl', "class ImplA { A a }")
        source('impl', "class ImplB { B b }")
        compile(gradleVersion)
        takeSnapshot()

        when:
        // the body of A changes its class file only, the method added to B changes its ABI
        a.text = "class A { void foo() { int unused = 0 } }"
        b.text = "class B { void bar() { }; void baz() { } }"
        compile(gradleVersion)

        then:
        recompiledClasses() == ['ImplB'] as Set

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "recompiles nothing when a classpath class differs only in an entry for the nested class of another class on Gradle #gradleVersion"() {
        given:
        file('settings.gradle') << "enableFeaturePreview('GROOVY_COMPILATION_AVOIDANCE')\n"
        file('api/build.gradle').text = """
            plugins {
                id 'groovy'
                id 'io.github.jdaugherty.incremental-groovy'
            }
            dependencies { implementation localGroovy() }
            tasks.withType(GroovyCompile).configureEach {
                options.incremental = true
            }
        """
        // Groovy lists the helper class of Named among the inner classes of Thing when it compiles them together, and
        // leaves it out when Named is already compiled, as it is once the change to Util recompiles Thing alone. Gradle's
        // ABI of the classpath keeps that entry, so the task runs, and the compiler, whose ABI leaves it out, finds
        // nothing to recompile
        File util = source('api', "class Util { static String greeting() { 'hello' } }")
        source('api', "trait Named { String describe() { 'named' } }")
        source('api', "class Thing implements Named { String go() { Util.greeting() } }")
        source('impl', "class UsesThing { Thing thing }")
        compile(gradleVersion)
        takeSnapshot()
        byte[] thing = file('api/build/classes/groovy/main/Thing.class').bytes

        when:
        util.text = "class Util { static String greeting() { 'hi' } }"
        BuildResult result = compile(gradleVersion, '--info')

        then:
        file('api/build/classes/groovy/main/Thing.class').bytes != thing
        result.output.contains("Task ':impl:compileIncrementalGroovy' is not up-to-date")
        result.task(':impl:compileIncrementalGroovy').outcome.name() == 'UP_TO_DATE'
        recompiledClasses().empty

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "fails to compile a class that refers to a class removed from a changed source on Gradle #gradleVersion"() {
        given:
        File a = file('impl/src/main/groovy/A.groovy')
        a.text = "class A1 {}\nclass A2 {}\n"
        source('impl', "class B { A2 a2 }")
        compile(gradleVersion)

        when:
        a.text = "class A1 {}\n"
        BuildResult result = runner(gradleVersion, ':impl:compileGroovy').buildAndFail()

        then:
        result.output.contains('unable to resolve class A2')

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "compiles incrementally from the configuration cache on Gradle #gradleVersion"() {
        given:
        File util = staleClassWhoseSuperclassIsRecompiled()
        compile(gradleVersion, '--configuration-cache')
        takeSnapshot()

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        BuildResult result = compile(gradleVersion, '--configuration-cache', '--info')

        then:
        result.output.contains('Reusing configuration cache.')
        result.output.contains('Incremental compilation of 2 classes')
        recompiledClasses() == ['Util', 'Base'] as Set

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "is up-to-date when nothing changed, and leaves compilation that is not incremental to Gradle on #gradleVersion"() {
        given:
        File util = source('impl', "class Util { static String zone(String id) { id?.trim() } }")
        file('impl/build.gradle') << """
            tasks.withType(GroovyCompile).configureEach {
                options.incremental = providers.gradleProperty('incremental').present
            }
        """
        compile(gradleVersion, '-Pincremental')

        expect:
        compile(gradleVersion, '-Pincremental').task(':impl:compileIncrementalGroovy').outcome.name() == 'UP_TO_DATE'

        when:
        util.text = "class Util { static String zone(String id) { id } }"
        BuildResult result = compile(gradleVersion, '--info')

        then:
        result.task(':impl:compileIncrementalGroovy').outcome.name() == 'SUCCESS'
        !result.output.contains('Incremental compilation of')

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "the task of Gradle is configured as before and does not compile on Gradle #gradleVersion"() {
        given:
        source('impl', "class A {}")
        file('impl/build.gradle') << """
            tasks.withType(GroovyCompile).configureEach {
                groovyOptions.listFiles = true
                timeout = java.time.Duration.ofMinutes(7)
            }
            compileGroovy {
                destinationDirectory = layout.buildDirectory.dir('custom-classes')
            }
            tasks.named('compileIncrementalGroovy') {
                doLast { println "timeout: \${timeout.get().toMinutes()} minutes" }
            }
        """

        when:
        BuildResult result = runner(gradleVersion, ':impl:classes').build()

        then:
        result.task(':impl:compileGroovy').outcome.name() == 'SKIPPED'
        result.task(':impl:compileIncrementalGroovy').outcome.name() == 'SUCCESS'
        file('impl/build/custom-classes/A.class').file
        result.output.contains('Source files to be compiled:')
        result.output.contains('timeout: 7 minutes')

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "compiles the test source set against the main one on Gradle #gradleVersion"() {
        given:
        source('impl', "class A { String name() { 'a' } }")
        file('impl/src/test/groovy/ATest.groovy').text = "class ATest { A a = new A() }"

        when:
        BuildResult result = runner(gradleVersion, ':impl:testClasses').build()

        then:
        result.task(':impl:compileIncrementalTestGroovy').outcome.name() == 'SUCCESS'
        file('impl/build/classes/groovy/test/ATest.class').file

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "takes the output from the build cache on Gradle #gradleVersion"() {
        given:
        file('settings.gradle') << """
            buildCache { local { directory = file('build-cache') } }
        """
        source('impl', "class A {}")
        compile(gradleVersion, '--build-cache')

        when:
        assert file('impl/build').deleteDir()
        BuildResult result = compile(gradleVersion, '--build-cache')

        then:
        result.task(':impl:compileIncrementalGroovy').outcome.name() == 'FROM_CACHE'
        file('impl/build/classes/groovy/main/A.class').file

        where:
        gradleVersion << GRADLE_VERSIONS
    }

    def "does nothing when turned off"() {
        given:
        File util = staleClassWhoseSuperclassIsRecompiled()
        file('gradle.properties') << 'incrementalGroovy.enabled=false\n'
        compile(GRADLE_VERSIONS.last())

        when:
        util.text = "class Util { static String zone(String id) { id?.trim()?.toUpperCase() } }"
        BuildResult result = runner(GRADLE_VERSIONS.last(), ':impl:compileGroovy').buildAndFail()

        then:
        result.output.contains('NoClassDefFoundError')
    }
}
