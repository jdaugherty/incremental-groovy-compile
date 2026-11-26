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
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskOperationResult
import org.gradle.tooling.events.task.TaskSkippedResult
import org.gradle.tooling.events.task.TaskSuccessResult
import org.gradle.tooling.model.build.BuildEnvironment

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs builds of a project through the Tooling API, in daemons of its own that it stops when it is closed.
 */
@CompileStatic
class BuildRunner implements Closeable {
    private final GradleConnector connector
    private final ProjectConnection connection

    /**
     * @param gradleVersion the version of Gradle to run, or null for the installation
     */
    BuildRunner(File projectDir, File gradleUserHome, File gradleInstallation, String gradleVersion) {
        connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDir)
            .useGradleUserHomeDir(gradleUserHome)
        if (gradleVersion) {
            connector.useGradleVersion(gradleVersion)
        } else {
            connector.useInstallation(gradleInstallation)
        }
        connection = connector.connect()
    }

    BuildRun run(List<String> tasks, List<String> arguments, File log) {
        Map<String, TaskRun> taskRuns = new ConcurrentHashMap<>()
        ProgressListener listener = { ProgressEvent event ->
            if (event instanceof TaskFinishEvent) {
                TaskFinishEvent finish = (TaskFinishEvent) event
                taskRuns[finish.descriptor.taskPath] = new TaskRun(outcomeOf(finish.result), finish.result.endTime - finish.result.startTime)
            }
        } as ProgressListener
        ByteArrayOutputStream output = new ByteArrayOutputStream()

        long start = System.nanoTime()
        boolean succeeded = true
        try {
            connection.newBuild()
                .forTasks(tasks as String[])
                .withArguments((['--offline', '--stacktrace'] + arguments) as String[])
                .setStandardOutput(output)
                .setStandardError(output)
                .addProgressListener(listener, EnumSet.of(OperationType.TASK))
                .run()
        } catch (BuildException ignored) {
            succeeded = false
        }
        long millis = (long) ((System.nanoTime() - start) / 1_000_000L)

        String text = output.toString(StandardCharsets.UTF_8.name())
        log.parentFile.mkdirs()
        log.setText(text, 'UTF-8')
        return new BuildRun(succeeded, millis, taskRuns, succeeded ? null : failureOf(text))
    }

    String gradleVersion() {
        return connection.getModel(BuildEnvironment).gradle.gradleVersion
    }

    @Override
    void close() {
        connection.close()
        // stops the daemons this connector started, and no others
        connector.disconnect()
    }

    private static String outcomeOf(TaskOperationResult result) {
        if (result instanceof TaskSuccessResult) {
            TaskSuccessResult success = (TaskSuccessResult) result
            return success.upToDate ? 'UP-TO-DATE' : success.fromCache ? 'FROM-CACHE' : 'EXECUTED'
        }
        if (result instanceof TaskSkippedResult) {
            return ((TaskSkippedResult) result).skipMessage
        }
        if (result instanceof TaskFailureResult) {
            return 'FAILED'
        }
        return 'UNKNOWN'
    }

    /**
     * The line of the output that tells best why the build failed.
     */
    private static String failureOf(String output) {
        List<String> lines = output.readLines()*.trim()
        String line = lines.find { it.contains('NoClassDefFoundError') } ?:
            lines.find { it.contains('unable to resolve class') } ?:
            lines[lines.indexOf('* What went wrong:') + 1]
        line = line?.replaceFirst(/^.*?(java\.lang\.)?(NoClassDefFoundError)/, '$2')
        return line?.length() > 200 ? line.substring(0, 200) + '...' : line
    }

    /**
     * The outcome of a build.
     */
    static class BuildRun {
        final boolean succeeded
        final long millis
        final Map<String, TaskRun> tasks
        /** Why the build failed, or null */
        final String failure

        BuildRun(boolean succeeded, long millis, Map<String, TaskRun> tasks, String failure) {
            this.succeeded = succeeded
            this.millis = millis
            this.tasks = tasks
            this.failure = failure
        }
    }

    /**
     * The outcome of a task, as Gradle reports it, and how long it took.
     */
    static class TaskRun {
        final String outcome
        final long millis

        TaskRun(String outcome, long millis) {
            this.outcome = outcome
            this.millis = millis
        }
    }
}
