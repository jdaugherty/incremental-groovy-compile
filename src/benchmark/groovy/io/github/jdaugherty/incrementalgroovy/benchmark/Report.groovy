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
import io.github.jdaugherty.incrementalgroovy.benchmark.Benchmark.Settings
import io.github.jdaugherty.incrementalgroovy.benchmark.Measurement.ModuleResult

/**
 * The results of a benchmark, as Markdown and as CSV. Times are medians of the measured runs.
 */
@CompileStatic
class Report {
    private final Settings settings
    private final Map<String, String> environment
    private final Map<String, Integer> classCounts
    private final List<Measurement> measurements

    Report(Settings settings, Map<String, String> environment, Map<String, Integer> classCounts, List<Measurement> measurements) {
        this.settings = settings
        this.environment = environment
        this.classCounts = classCounts
        this.measurements = measurements
    }

    String markdown() {
        StringBuilder md = new StringBuilder()
        String modules = ProjectGenerator.MODULES.collect { "`${it}` ${count(classCounts[it] ?: 0)}" }.join(', ')
        md << "The `${settings.shape.name}` project, generated with seed ${settings.seed}, compiles to ${modules} classes. "
        md << "Gradle ${environment['gradle-version']}, Groovy ${environment['groovy-version']}, Java ${environment['java-version']}, "
        md << "${environment['os']} with ${environment['processors']} processors. "
        md << "Each setup started with ${settings.warmUpBuilds} clean builds to warm up. "
        if (settings.warmUps > 0) {
            md << "Each change was made ${settings.warmUps + settings.runs} times, the first ${settings.warmUps} to warm up. "
        }
        md << "The times are medians of ${settings.runs} runs, from the start of the build that follows the change to the end of "
        md << 'the build that compiles it, a failed build and the build after it included. The classes are the class files written.\n\n'

        boolean compare = Setup.GRADLE in settings.setups && Setup.PLUGIN in settings.setups
        md << "| Change | ${settings.setups*.title.join(' | ')} |${compare ? ' Plugin compared to Gradle |' : ''}\n"
        md << "|---|${settings.setups.collect { '---' }.join('|')}|${compare ? '---|' : ''}\n"
        for (Scenario scenario : settings.scenarios) {
            md << "| ${scenario.title} | ${settings.setups.collect { summary(measured(it, scenario)) }.join(' | ')} |"
            if (compare) {
                double gradle = median(measured(Setup.GRADLE, scenario)*.millis)
                double plugin = median(measured(Setup.PLUGIN, scenario)*.millis)
                md << " ${speedUp(gradle, plugin)} |"
            }
            md << '\n'
        }

        md << '\nWhat each compile task did, with the time it took and the classes it wrote, and the time the plugin took '
        md << 'to extract the ABI of the compile classpath when it changed:\n'
        for (Scenario scenario : settings.scenarios) {
            md << "\n#### ${scenario.title}\n\n"
            md << "| | Build | ${ProjectGenerator.MODULES.collect { "`${it}`" }.join(' | ')} |\n"
            md << "|---|---|${ProjectGenerator.MODULES.collect { '---' }.join('|')}|\n"
            for (Setup setup : settings.setups) {
                List<Measurement> runs = measured(setup, scenario)
                md << "| ${setup.title} | ${seconds(median(runs*.millis))} | "
                md << ProjectGenerator.MODULES.collect { String module -> moduleCell(runs.collect { it.modules[module] }) }.join(' | ')
                md << ' |\n'
            }
            Set<String> failures = new LinkedHashSet<>()
            for (Setup setup : settings.setups) {
                measured(setup, scenario).each { Measurement run -> run.failures.each { failures << "${setup.title}: `${it}`".toString() } }
            }
            if (failures) {
                md << '\nFailed with:\n\n'
                failures.each { md << "- ${it}\n" }
            }
        }
        return md.toString()
    }

    String csv() {
        List<String> header = ['setup', 'scenario', 'run', 'warm_up', 'millis', 'builds', 'failure']
        ProjectGenerator.MODULES.each { String module ->
            header.addAll(["${module}_outcome", "${module}_compile_millis", "${module}_classpath_abi_millis", "${module}_classes"].collect { it.toString() })
        }
        List<String> lines = [header.join(',')]
        for (Measurement measurement : measurements) {
            List<Object> values = [measurement.setup.id, measurement.scenario.id, measurement.run + 1, measurement.warmUp, measurement.millis,
                                   measurement.builds, quote(measurement.failures ? measurement.failures.first() : '')]
            ProjectGenerator.MODULES.each { String module ->
                ModuleResult result = measurement.modules[module]
                values.addAll([result.outcome, result.compileMillis, result.classpathAbiMillis, result.recompiledClasses])
            }
            lines << values.join(',')
        }
        return lines.join('\n') + '\n'
    }

    static String seconds(Number millis) {
        return String.format('%.1f s', millis.doubleValue() / 1000d)
    }

    private List<Measurement> measured(Setup setup, Scenario scenario) {
        return measurements.findAll { it.setup == setup && it.scenario == scenario && !it.warmUp }
    }

    private static String summary(List<Measurement> runs) {
        if (runs.empty) {
            return ''
        }
        String result = "${seconds(median(runs*.millis))}, ${count((int) median(runs*.recompiledClasses))} classes"
        int failed = runs.count { Measurement run -> run.failures } as int
        if (failed == 0) {
            return result
        }
        return "fails${failed < runs.size() ? " ${failed} of ${runs.size()} times" : ''}, then ${result}"
    }

    private static String moduleCell(List<ModuleResult> results) {
        String outcome = results*.outcome.countBy { it }.max { it.value }.key
        String cell = outcome == 'EXECUTED' ?
            "${count((int) median(results*.recompiledClasses))} classes, ${seconds(median(results*.compileMillis))}" :
            outcome.toLowerCase()
        double classpathAbi = median(results*.classpathAbiMillis)
        if (classpathAbi >= 50) {
            cell += ", classpath ABI ${seconds(classpathAbi)}"
        }
        return results.any { it.failed } ? "fails, then ${cell}" : cell
    }

    private static String speedUp(double gradle, double plugin) {
        // a quarter of a second is within what builds of the same thing vary by
        if (plugin <= 0 || Math.abs(gradle - plugin) < 250) {
            return 'the same'
        }
        double ratio = gradle / plugin
        return ratio > 1 ? String.format('%.1f× faster', ratio) : String.format('%.1f× slower', 1 / ratio)
    }

    private static double median(List<? extends Number> values) {
        if (values.empty) {
            return 0
        }
        double[] sorted = values.collect { it.doubleValue() } as double[]
        Arrays.sort(sorted)
        int middle = sorted.length.intdiv(2)
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2d
    }

    private static String count(int count) {
        return String.format('%,d', count)
    }

    private static String quote(String value) {
        return '"' + value.replace('"', '""') + '"'
    }
}
