# Incremental Groovy Compile

A Gradle plugin that compiles Groovy, and Java together with it, incrementally. Its compiler is made from
Gradle's own, with the changes proposed to Gradle in
[gradle/gradle#39622](https://github.com/gradle/gradle/pull/39622), so that builds do not have to wait for a
Gradle release that has them.

This project is not affiliated with or endorsed by Gradle, Inc.

## What it fixes

With `options.incremental = true`, Gradle moves the class files of the classes it recompiles out of the output
directory for the time of the compilation. The Groovy compiler does not only read the classes of the previous
compilation, though, it loads some of them: the helper classes of precompiled traits, and whatever an AST
transformation asks for, as the GORM transformations of Grails do for domain classes. When such a class refers
to a class that is being recompiled, the compilation fails:

```
> Unrecoverable compilation error: startup failed:
  General error during canonicalization: java.lang.NoClassDefFoundError: Base
```

Gradle then falls back to a full recompilation, or fails the build
([#22519](https://github.com/gradle/gradle/issues/22519), [#22531](https://github.com/gradle/gradle/issues/22531),
[#22814](https://github.com/gradle/gradle/issues/22814)).

With the plugin:

- The JVM finds the classes being recompiled in their stashed class files, while the compiler still resolves
  them from source. Nothing more is recompiled for it.
- A recompiled class with a Java source has all of its dependents recompiled, as the Groovy compiler cannot
  read a class that refers to it until the Java compiler has run.
- The classes of the compile classpath are compared by their ABI, which leaves out the entries a class has for
  the nested classes of other classes. A dependency whose ABI did not change recompiles nothing, and one whose
  ABI changed recompiles only the classes that use what changed.

## What it saves

The [benchmark](#benchmark) generates a project laid out like a Grails application, makes changes to it, and
compiles them with Gradle alone and with the plugin. For the `large` project, 7,588 classes in three modules, on
a GitHub-hosted runner with 4 processors, Java 21, Gradle 9.9.2 and Groovy 4.0.33, these are the median times from
a change to its compiled classes, and the classes compiled:

| Change                                                       |        Gradle |       Gradle, incremental |        Plugin |
|--------------------------------------------------------------|--------------:|--------------------------:|--------------:|
| A method body of a service                                   |  6.9 s, 3,000 |                  1.6 s, 2 |      1.6 s, 2 |
| A method body of an entity                                   | 13.7 s, 4,542 |                 3.1 s, 61 |     3.1 s, 61 |
| A method body of a utility in `core` that many entities call | 13.7 s, 4,542 | fails, then 17.4 s, 4,542 |    7.1 s, 680 |
| A method body of a utility in `lib`                          |    1.6 s, 46 |                 1.6 s, 46 |     1.7 s, 46 |
| A public method added to an entity                           | 18.2 s, 7,542 |                3.6 s, 101 |    4.0 s, 101 |
| A public method added to an entity that most areas use       | 18.5 s, 7,542 |             17.0 s, 5,762 | 17.2 s, 5,762 |
| Clean build                                                  | 18.1 s, 7,588 |             18.4 s, 7,588 | 18.6 s, 7,588 |

Gradle compiles Groovy non-incrementally by default, so any change to a module compiles all of it, and a change
to its ABI all of the modules that depend on it. With `options.incremental = true` it compiles as little as the
plugin does, until the compiler loads a class whose superclass or dependency is being recompiled, here a trait
helper:

```
NoClassDefFoundError: Unable to configure example.core.support.Describable$Trait$Helper due to missing dependency example/core/support/Describer
```

The build after a failure compiles the module in full. It failed the same way the first time a public method was
added after that, when the AST transformation loaded an entity whose class file was stashed. The plugin compiles
the classes that Gradle's incremental compilation selects, without failing. A change to a class that most of the
project depends on recompiles most of it either way.

Smaller modules leave less to save. With the entities spread over 15 modules of about 500 classes
(`-Pbenchmark.modules=15`), Gradle compiles a module in 3.5 s, and the plugin is 2.1 times as fast for a method body
of a service and 1.5 times for one of an entity.

## Usage

Releases are published to Maven Central, where Gradle does not look for plugins unless it is told to:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Apply the plugin to every project that compiles Groovy:

```groovy
plugins {
    id 'groovy'
    id 'io.github.jdaugherty.incremental-groovy' version '<version>'
}

tasks.withType(GroovyCompile).configureEach {
    options.incremental = true
}
```

The build is configured as before, through the `GroovyCompile` tasks. See below for what the plugin does with
them.

Enabling the `GROOVY_COMPILATION_AVOIDANCE` feature preview is recommended. Without it, the compile classpath is
also the classpath of the AST transformations, and any change to it recompiles everything.

### Supported versions

| Plugin | Gradle        | Compiler daemon JVM |
|--------|---------------|---------------------|
| 0.1    | 9.8.x, 9.9.x  | 8 and later         |

The compiler uses internal classes of Gradle, so each version of the plugin supports the Gradle versions it was
tested with. On any other version it logs a warning and leaves compilation to Gradle. The Gradle property
`incrementalGroovy.enabled` overrides that: `true` to try the plugin on an untested version, `false` to turn it
off.

The configuration cache and the build cache are supported.

## What the plugin adds

For each source set with Groovy sources, the plugin adds an `IncrementalGroovyCompile` task that compiles the
sources, `compileIncrementalGroovy` for `main`.

The `compileGroovy` task of the source set stays, as the place where the compilation is configured, by the
build and by other plugins, and as what other tasks depend on. It no longer compiles: it is disabled and depends
on `compileIncrementalGroovy`, which takes its sources, classpaths, options, toolchain and destination directory
from it.

Two things to know:

- The options are taken from `compileGroovy` as they are when the build is configured. A change made to them
  later, while tasks run, is not seen.
- The timeout of `compileGroovy` is taken over too. Actions added to it with `doFirst` or `doLast` are not: it
  does not run. Add them to the `IncrementalGroovyCompile` tasks.
- A `GroovyCompile` task that does not belong to a source set is left to Gradle.

## How it is made

Gradle has no extension point for its incremental compiler, so the plugin has its own, made from Gradle's:

- The classes of Gradle's Groovy compiler that the fix touches are copied into
  `io.github.jdaugherty.incrementalgroovy.internal`, with the changes of the pull request applied. Each names the
  Gradle source file it comes from. The first commits of this repository are the unchanged import and the
  changes, in that order.
- Everything else is still Gradle's: the compile spec, the worker daemon, and the Java and Groovy compiler
  classes that are not changed.
- The compiler shares Guava, ASM and fastutil types with Gradle, which plugins cannot see. It is loaded by a
  class loader that sees what Gradle's Groovy compiler sees, and the tasks reach it through one interface,
  `CompilerBackend`.
- In the compiler daemon, the plugin's compiler classes are added to the class loader of Gradle's.

The plugin and its tasks are written in Groovy. The compiler is Java, as the classes of Gradle it is made from
are, and so are the classes that run in the compiler daemon, which must not depend on the Groovy version that
the daemon compiles with.

## Building

```
./gradlew build
```

The functional tests run builds with each supported Gradle version, with and without the plugin.

### Moving to a newer Gradle version

The `importUpstream` task copies the classes from a Gradle checkout. To see what a new Gradle version changed in
them, import both versions into scratch directories and compare:

```
./gradlew importUpstream -PgradleCheckout=~/gradle -PgradleRef=v9.9.2 -PimportInto=/tmp/old
./gradlew importUpstream -PgradleCheckout=~/gradle -PgradleRef=v9.10.0 -PimportInto=/tmp/new
diff -ru /tmp/old /tmp/new
```

Apply the differences, raise the supported versions in `IncrementalGroovyPlugin` and in `build.gradle`, and run
the tests.

### Benchmark

```
./gradlew benchmark
```

generates a project, changes it, and measures the builds that compile the change, with Gradle alone and with the
plugin. It is not part of `build`, as it takes about ten minutes for the default project and 25 for `large` on a
GitHub-hosted runner; `build` only compiles it. The results are written to `build/benchmark/results.md`, with
each run in `results.csv`, the build logs in `logs` and the generated
projects in `projects`, where they can be built by hand.

The Benchmark workflow runs it on a fresh GitHub-hosted runner, where no other build competes with it: start it
from the Actions tab, and the results are in the summary of the run and in its artifact.

The generated project is laid out like an application on a persistence framework such as Grails' GORM:

- `transform`, a global AST transformation that reads the associations of persistent classes the way GORM does:
  it loads the classes of the associated entities that were compiled before.
- `lib`, utilities and a trait, used by the other modules.
- `core`, the base classes, traits and utilities of the entities, and a few shared entities.
- Entities in groups called areas, each associated with entities of its area and with shared ones, and the
  command objects that bind them; and a service and a controller for half of the entities.
- `app`, the application.

By default the entities are in `core`, and their services and controllers in `app`. `-Pbenchmark.modules=<n>`
spreads the areas over `n` feature modules instead, each holding the entities, services and controllers of its
areas as a Grails plugin would, with `app` depending on all of them. The modules are built in parallel.

It is compiled in three setups:

- **Gradle**: Gradle as it compiles Groovy by default, every class of a module whose source or compile classpath
  changed.
- **Gradle, incremental**: Gradle with `options.incremental = true`.
- **Plugin**: the plugin, with `options.incremental = true`.

All three enable the `GROOVY_COMPILATION_AVOIDANCE` feature preview, so that a module whose compile classpath
changed only in its implementation stays up-to-date.

Each scenario makes a change and builds `:app:compileGroovy`: nothing; a method body of a service, of an entity, of
a utility in `core` that many entities call, and of a utility in `lib`; a public method added to an entity, and to
a shared entity in `core` that most areas use; and a clean build. A build that fails is run again, as one does
when a build fails for no fault of the code, and the time of both counts.

Every setup keeps its daemons for the whole benchmark, and the setups take turns at each change, so that
whatever else the machine does slows them alike. Each setup needs memory for a Gradle daemon of 3 GB and a
compiler daemon of up to 2 GB for each module it compiles at the same time. The Gradle properties that change what
is measured:

| Property                  | Default            | What it is                                                          |
|---------------------------|--------------------|---------------------------------------------------------------------|
| `benchmark.size`          | `medium`           | `small`, `medium` or `large`: 8, 25 or 75 areas of 40 entities      |
| `benchmark.modules`       | `0`                | the feature modules to spread the areas over, up to one per area    |
| `benchmark.seed`          | `1`                | generates a different project of the same size                      |
| `benchmark.warmUpBuilds`  | `2`                | clean builds of each setup before the changes, to warm up the JIT   |
| `benchmark.warmUps`       | `1`                | runs of each change that are not measured                           |
| `benchmark.runs`          | `5`                | runs of each change that are measured                               |
| `benchmark.setups`        | all                | `gradle`, `gradle-incremental` and `plugin`, comma-separated        |
| `benchmark.scenarios`     | all                | the scenarios to run, comma-separated, as `results.csv` names them  |
| `benchmark.gradleVersion` | the one running it | the Gradle version that builds the generated projects               |

### Releasing

Pushing a tag `v<version>` runs the release workflow, which builds that version, signs it and uploads it to the
[Central Portal](https://central.sonatype.com/publishing/deployments). The upload waits there to be released by
hand. A release cannot be changed or removed.

The workflow needs the repository secrets `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`, the user token
of the portal account, and `SIGNING_KEY` and `SIGNING_PASSWORD`, the ASCII-armored PGP private key and its
passphrase. The public key has to be on a key server that Maven Central reads.

## License

[Apache License 2.0](LICENSE), the license of Gradle.

The classes that are derived from Gradle keep Gradle's license header and copyright, followed by a note naming
the Gradle source file they come from and stating that they were changed. [NOTICE](NOTICE) carries the
attribution. Both files are in the jar under `META-INF`.

`./gradlew check` runs [Apache RAT](https://creadur.apache.org/rat/), which fails the build when a file has no
license header.
