---
title: "Every Launcher Carries the Packaged Options, and Crash Reports Land in the Log Folder"
labels: ["bug", "build", "packaging", "logging", "reliability"]
---

# Every Launcher Carries the Packaged Options, and Crash Reports Land in the Log Folder

## Motivation

Story 336 gives the application a log file under `<settings>/logs` and a session banner, and names its logging manager on every launch path (`FAILURE_SURFACING_DESIGN_BOOK.md` §2.8 and §4.2). Its review found the launchers and the crash evidence still uneven (story 336, rulings E12.9, E12.10 and E-D9.8):

- **The jlink launcher passes no `--enable-native-access`.** The jpackage launcher passes `--enable-native-access=daw.core,daw.sdk,daw.app` through its `--java-options` (`daw-app/pom.xml:371-372`). The jlink launcher scripts `bin/daw` and `bin/daw.bat` carry only the JVM options that the javafx-maven-plugin `jlink` configuration (`daw-app/pom.xml:273-305`) writes into their `JLINK_VM_OPTIONS`. Its `<options>` (`:292-294`) name only story 336's logging manager, so they do not include it. The POM's own comment on the jpackage option (`daw-app/pom.xml:361-370`) says what that costs: a native-access warning per module on its first downcall, which a future JDK turns into an error (dawg-native-libs "### Diagnose `UnsupportedOperationException` / `UnsatisfiedLinkError` at runtime", item 5).
- **No launcher passes `@<settings>/zgc.conf`.** `DawLauncher` writes the ZGC profile into the settings directory at every launch, and its class Javadoc (`DawLauncher.java:27-30` at story 336's start) says "The shipped launcher script references that file via `java @<settings>/zgc.conf ...`". Neither the jpackage launcher nor the jlink scripts do. Only the manual commands in `docs/java26-setup.md` §3 and §5 pass it. The Javadoc's claim is false, and this story corrects it.
- **Native crash reports land outside the log folder.** On an ASIO rig the most common hard crash is a driver access violation. The JVM's `hs_err_pid*.log` then lands in the working directory, which for an installed app is often not writable, or in the temporary directory. The user who sends the log folder does not send the crash report.
- **The installer's version is a placeholder that does not follow the build.** Since story 336, the session banner names the build's own version on every launcher: the version in `daw.app`'s module descriptor, `0.1.0-SNAPSHOT` today. No POM passes `--module-version`, yet the compiled descriptor carries the project version (`javap -v` on `daw-app/target/classes/module-info.class` prints `module daw.app@0.1.0-SNAPSHOT`), and every launcher runs `daw.app` as a named module. So the jlink scripts `bin/daw` and `bin/daw.bat`, `mvn javafx:run`, the manual commands in `docs/java26-setup.md` and the jpackage exe all write that version. The jpackage exe's banner also names jpackage's `--app-version`, labelled as the installer's (for example `(installer 1.0.0)`); its launcher passes that value as `-Djpackage.app-version` (the app-image's `.cfg` holds `java-options=-Djpackage.app-version=1.0.0`). The value is the literal `1.0.0` at `daw-app/pom.xml:353-356`, whose comment reads "jpackage requires a numeric version; the project version is a -SNAPSHOT string". It does not change with the build, so every installed build's banner labels the same `1.0.0` as its installer version.
- **The documented benchmark does not exist.** `docs/java26-setup.md` §5 (Benchmarking) tells the reader to run `DawLauncher` with `--benchmark=stress --tracks=64 --inserts=4 --sample-rate=48000 --buffer=64`. Nothing in daw-app reads `--benchmark`.
- **"Log closed at application exit." is not proof of a clean exit.** When an FFM upcall throws, the JDK prints the stack trace to standard error and calls `System.exit(1)` (`jdk/internal/foreign/abi/SharedUtils.java:306-315`, JDK 27). The shutdown hooks still run, so the log file ends with the same closing line as after a clean exit, and the stack trace is lost with standard error. The JDK already logs every `Runtime.exit`, with its status and a stack trace, at `System.Logger` DEBUG on the logger `java.lang.Runtime` (`java/lang/Shutdown.java:176-193`, JDK 21 and later). The sink records INFO and above, so that record is dropped. The application never calls `System.exit` itself (`git grep` finds no `System.exit`, `Runtime.exit` or `halt` call in main source). Letting that one record through to the log file would therefore mark every exit that goes through `System.exit`, and here each of those is abnormal.

## Goals

- **One list of packaged JVM options, on every launcher.** The jpackage exe, `bin/daw`, `bin/daw.bat` and `mvn javafx:run` pass the same options: `--enable-native-access=daw.core,daw.sdk,daw.app`, story 336's logging manager, and the crash-report option below.
- **The ZGC profile claim is true.** Either the shipped launchers pass `@<settings>/zgc.conf`, or `DawLauncher`'s class Javadoc and `docs/java26-setup.md` stop saying that they do. The studio engineer rules which.
- **Crash reports sit next to the log.** Every launcher passes `-XX:ErrorFile=<settings>/logs/hs_err_pid%p.log`, so Help ▸ Open Log Folder shows a native crash report beside the log file.
- **The installer's version follows the build.** jpackage's `--app-version` is derived from the project version instead of the literal `1.0.0`, so the installer version that the exe's banner labels changes with the build. jpackage requires a numeric version and the project version is a `-SNAPSHOT` string, so how one maps to the other is this story's design question; the studio engineer rules it. The banner keeps naming the module version on every launcher, and the installer's version never stands in for it (story 336, ruling R-V4).
- **The benchmark documentation matches the code.** The `--benchmark=stress` command either works or leaves `docs/java26-setup.md`. The studio engineer rules which.
- **An exit through `System.exit` leaves its mark in the log file.** The `java.lang.Runtime` logger's DEBUG record (FINE in `java.util.logging`) reaches the log file with its status and stack trace, and no other FINE record does.

## Acceptance Criteria

1. Every launcher passes the same packaged option list.
2. A native crash report is written to `<settings>/logs`.
3. jpackage's `--app-version` is derived from the project version, and the session banner still names the module version on every launcher.
4. `DawLauncher`'s class Javadoc and `docs/java26-setup.md` describe only what the launchers do.
5. An exit through `System.exit` writes its status and stack trace to the log file before "Log closed at application exit.".

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A POM test: the jpackage `--java-options` and the javafx-maven-plugin `<options>` name the same list, including `--enable-native-access=daw.core,daw.sdk,daw.app`, story 336's logging manager and `-XX:ErrorFile`.
- Against the built jlink image (`-DskipNativeBuild=true` skips jpackage but still builds the jlink image, `daw-app/pom.xml`): the `JLINK_VM_OPTIONS` line of `bin/daw` and of `bin/daw.bat` holds that list.
- A child JVM set up as the packaged launcher calls `System.exit(3)`: its log file holds the `Runtime.exit` record with status 3 before "Log closed at application exit.". A child that exits normally has no such record, and a FINE record from any other logger stays out.
- A child JVM set up as the packaged launcher in which an FFM upcall throws (for example a C library `qsort` comparator): the log file holds the `Runtime.exit` record with status 1.
- A POM test: the jpackage `--app-version` argument is derived from the project version, not a literal.
- Fault check, run once, turning a test red: `--enable-native-access` removed from the jlink options, so the POM test fails.

## Non-Goals

- The log sink, the logging manager, the exit hold and the banner's lines: story 336.
- What `zgc.conf` contains: `docs/java26-setup.md`.
- Sending crash reports anywhere, or reading them at the next launch.
- The native library build (`-DskipNativeBuild`): the dawg-native-libs skill.

## Technical Notes

- `daw-app/pom.xml`: the javafx-maven-plugin `jlink` configuration (`:273-305`; its `<options>` element, `:292-294`, becomes `JLINK_VM_OPTIONS` in the launcher scripts), the jpackage `--app-version` (`:353-356`) and the jpackage `--java-options` (`:371-372` for native access, `:381-382` for story 336's logging manager). jpackage is skipped with `-DskipNativeBuild=true`, and the jlink image is still built.
- Story 336's ruling R992-1 links the jlink image without stripping debug attributes (`<stripDebug>` and `<stripJavaDebugAttributes>` explicitly false), and the app-image's runtime is that image, through jpackage's `--runtime-image ${project.build.directory}/daw-runtime`. Any installer type this story adds (`--type exe` or `--type msi`), or any other jpackage execution, must pass the same `--runtime-image`, because jpackage's own jlink runs `--strip-debug` by default; `PackagedRuntimeKeepsLineNumbersTest` should then cover that execution too. Separately, JDK 27's jlink warns that the `2` argument of `--compress` (`<compress>2</compress>` in the jlink configuration) is deprecated and may be removed; javafx-maven-plugin 0.0.8 accepts only 0 to 2, so moving to `zip-6` needs a plugin upgrade or a jlink execution of its own. Ruling R992-1 also added nine lines to the jlink configuration at `daw-app/pom.xml:281`, so the POM lines this story cites from `:282` on are now nine lines further down (the `<options>` element is now at `:301-303`).
- How each launcher names the per-user settings path is this story's design question. `DawLauncher.userSettingsDirectory()` is the one place that resolves it today: `%APPDATA%\java-daw` on Windows, `~/Library/Application Support/java-daw` on macOS, and `$XDG_CONFIG_HOME/java-daw` or `~/.config/java-daw` elsewhere.
- `DawLauncher`'s class Javadoc and `installZgcConfig`; `README.md:51` (the jpackage exe) and `:57` (`bin/daw.bat`); `docs/java26-setup.md` §3 (the `zgc.conf` JVM options file) and §5 (Benchmarking).
- Story 336's banner and `LogSink` (daw-app, package `app.diagnostics`) and its logging manager (package `app.logging`). `System.Logger` DEBUG maps to `java.util.logging` FINE.
- Skills: javafx-application-design "## 2. Project structure & build" (jlink and jpackage for distributable apps); dawg-native-libs "## 4. Recipes", "### Diagnose `UnsupportedOperationException` / `UnsatisfiedLinkError` at runtime", item 5.
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §2.8 (diagnosability outlives the session) and §4.2 (the log sink).
- Cross-refs: story 336 (rulings E12.9, E12.10 and E-D9.8, and the risks its design left to a follow-up story: native crash reports that land outside the log folder; where the banner's version comes from, jpackage's version property being set only in the exe; and the jlink scripts' missing `--enable-native-access`, the `DawLauncher` Javadoc's false claim that a shipped launcher passes `@zgc.conf`, and the documented `--benchmark` command that nothing in daw-app implements), story 337 (the FFM upcall guards that make an upcall's throw rare).

## Status

- Deferred
