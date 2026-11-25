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
- The compile classpath is compared by its ABI, as the plugin extracts it. A dependency whose class files
  changed but whose ABI did not, leaves the compile task up-to-date, and one whose ABI changed recompiles only
  the classes that use what changed.

## Usage

The plugin is not published yet. Until it is, build it from source and include the build:

```groovy
// settings.gradle
pluginManagement {
    includeBuild('../incremental-groovy-compile')
}
```

Apply it to every project that compiles Groovy:

```groovy
plugins {
    id 'groovy'
    id 'io.github.jdaugherty.incremental-groovy'
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

For each source set with Groovy sources, the plugin adds two tasks, here for `main`:

- `compileIncrementalGroovy`, an `IncrementalGroovyCompile` task, compiles the sources.
- `extractGroovyCompileClasspathAbi` writes down the ABI of the compile classpath. It is the input that tells
  the compile task whether the classpath changed.

The `compileGroovy` task of the source set stays, as the place where the compilation is configured, by the
build and by other plugins, and as what other tasks depend on. It no longer compiles: it is disabled and depends
on `compileIncrementalGroovy`, which takes its sources, classpaths, options, toolchain and destination directory
from it.

Two things to know:

- The options are taken from `compileGroovy` as they are when the build is configured. A change made to them
  later, while tasks run, is not seen.
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

## License

[Apache License 2.0](LICENSE), the license of Gradle.

The classes that are derived from Gradle keep Gradle's license header and copyright, followed by a note naming
the Gradle source file they come from and stating that they were changed. [NOTICE](NOTICE) carries the
attribution. Both files are in the jar under `META-INF`.

`./gradlew check` runs [Apache RAT](https://creadur.apache.org/rat/), which fails the build when a file has no
license header.
