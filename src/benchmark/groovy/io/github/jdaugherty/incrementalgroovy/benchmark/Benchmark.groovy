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
import io.github.jdaugherty.incrementalgroovy.benchmark.BuildRunner.BuildRun
import io.github.jdaugherty.incrementalgroovy.benchmark.Measurement.ModuleResult

import java.nio.file.attribute.FileTime

/**
 * Generates a project for each setup, makes the changes of the scenarios to it, and measures the builds that compile
 * them. Writes {@code results.md} and {@code results.csv} to the output directory.
 * <p>
 * The daemons of all setups run for the whole benchmark, so it needs the memory of a Gradle daemon and a Groovy compiler
 * daemon for each, up to 5 GB a setup.
 * <p>
 * It is configured by system properties, which the {@code benchmark} task sets from the Gradle properties of the same
 * names:
 * <ul>
 *     <li>{@code benchmark.size}: {@code small}, {@code medium} (the default) or {@code large}</li>
 *     <li>{@code benchmark.seed}: generates a different project of the same size, 1 by default</li>
 *     <li>{@code benchmark.warmUpBuilds}: the clean builds of each setup before the scenarios, 2 by default</li>
 *     <li>{@code benchmark.warmUps}: the runs of each scenario that are not measured, 1 by default</li>
 *     <li>{@code benchmark.runs}: the runs of each scenario that are measured, 5 by default</li>
 *     <li>{@code benchmark.setups}: the setups to measure, comma-separated, all by default</li>
 *     <li>{@code benchmark.scenarios}: the scenarios to run, comma-separated, all by default</li>
 *     <li>{@code benchmark.gradleVersion}: the version of Gradle that builds the projects, the one that runs the
 *     benchmark by default</li>
 * </ul>
 */
@CompileStatic
class Benchmark {
    /** Compiles every module, as {@code app} depends on all of them */
    static final List<String> COMPILE = [':app:compileGroovy']

    private final Settings settings
    private final File logs

    Benchmark(Settings settings) {
        this.settings = settings
        this.logs = new File(settings.output, 'logs')
    }

    static void main(String[] args) {
        new Benchmark(Settings.fromSystemProperties()).run()
    }

    void run() {
        File projects = new File(settings.output, 'projects')
        projects.deleteDir()
        logs.deleteDir()

        List<Measurement> measurements = []
        Map<String, String> environment = [:]
        Map<String, Integer> classCounts = [:]
        Map<Setup, GeneratedProject> generated = [:]
        Map<Setup, BuildRunner> runners = [:]
        try {
            for (Setup setup : settings.setups) {
                File dir = new File(projects, setup.id)
                GeneratedProject project = new ProjectGenerator(settings.shape, settings.seed)
                    .generate(dir, setup, settings.repository, settings.pluginVersion)
                BuildRunner runner = new BuildRunner(dir, new File(settings.output, 'gradle-user-home'), settings.gradleInstallation, settings.gradleVersion)
                generated[setup] = project
                runners[setup] = runner

                println "${setup.title}: compiling the ${settings.shape.name} project in ${dir}"
                // takes the plugin as it was just published, rather than as an earlier run resolved it
                BuildRun first = runner.run(COMPILE, ['--refresh-dependencies'], log(setup, 'first-build'))
                if (!first.succeeded) {
                    throw new IllegalStateException("${setup.title} cannot compile the generated project: ${first.failure}. See ${log(setup, 'first-build')}")
                }
                if (environment.isEmpty()) {
                    environment.putAll(environmentOf(runner, setup))
                    ProjectGenerator.MODULES.each { String module -> classCounts[module] = project.classFiles(module).size() }
                }
                // the compiler is far slower until the JIT has compiled it, which takes more than one build
                for (int build = 1; build <= settings.warmUpBuilds; build++) {
                    println "${setup.title}: clean build ${build} of ${settings.warmUpBuilds} to warm up"
                    runner.run(['clean'] + COMPILE, [], log(setup, "warm-up-${build}"))
                }
            }

            // The setups take turns, so that whatever else the machine does meanwhile slows them alike. Each makes the
            // same changes in the same order.
            int revision = 0
            for (Scenario scenario : settings.scenarios) {
                for (int run = 0; run < settings.warmUps + settings.runs; run++) {
                    revision++
                    for (Setup setup : settings.setups) {
                        Measurement measurement = measure(runners[setup], generated[setup], setup, scenario, run, revision)
                        measurements << measurement
                        println describe(measurement)
                    }
                }
            }
        } finally {
            runners.values().each { BuildRunner runner -> runner.close() }
        }

        Report report = new Report(settings, environment, classCounts, measurements)
        String markdown = report.markdown()
        new File(settings.output, 'results.md').setText(markdown, 'UTF-8')
        new File(settings.output, 'results.csv').setText(report.csv(), 'UTF-8')
        println ''
        println markdown
        println "Written to ${new File(settings.output, 'results.md')} and results.csv, with the build logs in ${logs}"
    }

    private Measurement measure(BuildRunner runner, GeneratedProject project, Setup setup, Scenario scenario, int run, int revision) {
        Map<String, Map<String, FileTime>> before = ProjectGenerator.MODULES.collectEntries { String module ->
            [(module): project.classFiles(module)]
        }
        scenario.apply(project, revision)

        String name = "${scenario.id}-${run + 1}"
        List<BuildRun> builds = []
        builds << runner.run(scenario.clean ? ['clean'] + COMPILE : COMPILE, [], log(setup, name))
        if (!builds.last().succeeded) {
            // what one does when a build fails through no fault of the code: run it again
            builds << runner.run(COMPILE, [], log(setup, "${name}-again"))
        }
        if (!builds.last().succeeded) {
            builds << runner.run(['clean'] + COMPILE, [], log(setup, "${name}-clean"))
        }
        if (!builds.last().succeeded) {
            throw new IllegalStateException("${setup.title} cannot compile the project after '${scenario.title}', even after cleaning it: ${builds.last().failure}. See ${log(setup, "${name}-clean")}")
        }

        Map<String, ModuleResult> modules = [:]
        for (String module : ProjectGenerator.MODULES) {
            String task = setup.compileTask(module)
            Map<String, FileTime> previous = before[module]
            Map<String, FileTime> classFiles = project.classFiles(module)
            int recompiled = classFiles.count { String path, FileTime time -> previous[path] != time }.intValue()
            long compileMillis = (long) builds.sum(0L) { BuildRun build -> build.tasks[task]?.millis ?: 0L }
            String abiTask = setup.classpathAbiTask(module)
            long classpathAbiMillis = abiTask == null ? 0L : (long) builds.sum(0L) { BuildRun build -> build.tasks[abiTask]?.millis ?: 0L }
            boolean failed = builds.any { BuildRun build -> build.tasks[task]?.outcome == 'FAILED' }
            String outcome = builds.last().tasks[task]?.outcome ?: 'NOT RUN'
            modules[module] = new ModuleResult(outcome, compileMillis, classpathAbiMillis, recompiled, failed)
        }
        List<String> failures = builds.findAll { !it.succeeded }.collect { it.failure ?: 'unknown' }
        long millis = (long) builds.sum(0L) { BuildRun build -> build.millis }
        return new Measurement(setup, scenario, run, run < settings.warmUps, millis, failures, modules)
    }

    private Map<String, String> environmentOf(BuildRunner runner, Setup setup) {
        File log = log(setup, 'environment')
        runner.run(['environment'], [], log)
        Map<String, String> environment = [:]
        log.readLines().each { String line ->
            int separator = line.indexOf('=')
            if (separator < 0) {
                return
            }
            String key = line.substring(0, separator)
            if (key in ['groovy-version', 'java-version']) {
                environment[key] = line.substring(separator + 1)
            }
        }
        environment['gradle-version'] = runner.gradleVersion()
        environment['os'] = "${System.getProperty('os.name')} ${System.getProperty('os.arch')}".toString()
        environment['processors'] = Runtime.runtime.availableProcessors().toString()
        return environment
    }

    private File log(Setup setup, String name) {
        return new File(logs, "${setup.id}/${name}.log")
    }

    private String describe(Measurement measurement) {
        String run = "${measurement.run + 1}/${settings.warmUps + settings.runs}${measurement.warmUp ? ' (warm-up)' : ''}"
        String modules = ProjectGenerator.MODULES.collect { String module ->
            ModuleResult result = measurement.modules[module]
            String compiled = result.outcome == 'EXECUTED' ? "${result.recompiledClasses} classes in ${Report.seconds(result.compileMillis)}" : result.outcome
            String abi = result.classpathAbiMillis > 0 ? " (classpath ABI ${Report.seconds(result.classpathAbiMillis)})" : ''
            "${module} ${result.failed ? 'failed, then ' : ''}${compiled}${abi}"
        }.join(', ')
        return "[${measurement.setup.id}] ${measurement.scenario.id} ${run}: ${Report.seconds(measurement.millis)} in ${measurement.builds} build(s); ${modules}"
    }

    /**
     * What to measure, and where.
     */
    static class Settings {
        File output
        File repository
        String pluginVersion
        File gradleInstallation
        String gradleVersion
        ProjectShape shape
        long seed
        int warmUpBuilds
        int warmUps
        int runs
        List<Setup> setups
        List<Scenario> scenarios

        static Settings fromSystemProperties() {
            Settings settings = new Settings()
            settings.output = new File(required('benchmark.output'))
            settings.repository = new File(required('benchmark.repository'))
            settings.pluginVersion = required('benchmark.pluginVersion')
            settings.gradleInstallation = new File(required('benchmark.gradleInstallation'))
            settings.gradleVersion = System.getProperty('benchmark.gradleVersion')
            settings.shape = ProjectShape.named(System.getProperty('benchmark.size', 'medium'))
            settings.seed = Long.parseLong(System.getProperty('benchmark.seed', '1'))
            settings.warmUpBuilds = Integer.parseInt(System.getProperty('benchmark.warmUpBuilds', '2'))
            settings.warmUps = Integer.parseInt(System.getProperty('benchmark.warmUps', '1'))
            settings.runs = Integer.parseInt(System.getProperty('benchmark.runs', '5'))
            settings.setups = list('benchmark.setups').collect { Setup.byId(it) } ?: Setup.values().toList()
            settings.scenarios = list('benchmark.scenarios').collect { Scenario.byId(it) } ?: Scenario.ALL
            if (settings.runs < 1) {
                throw new IllegalArgumentException('benchmark.runs must be 1 or more')
            }
            return settings
        }

        private static String required(String name) {
            String value = System.getProperty(name)
            if (!value) {
                throw new IllegalArgumentException("The system property ${name} is missing. Run the benchmark with ./gradlew benchmark")
            }
            return value
        }

        private static List<String> list(String name) {
            return (System.getProperty(name) ?: '').split(',')*.trim().findAll()
        }
    }
}
