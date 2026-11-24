# libs

Build-time artifacts committed into the repository so that CI and contributors need no extra Maven
repository, credentials, or JDK zoo to build a line. Nothing here ships inside a release jar: the
downgrade tasks read these files and copy only the stubs a module actually uses.

## `jvmdowngrader-java-api-2.0.1-downgraded-7.jar`

JvmDowngrader's API stubs at class file 51 (Java 7). The `1.4.7` line needs this to emit class-51
bytecode, which is what Forge 1.4.7's FML reads without the ASM patch the line previously carried.

Upstream publishes `downgraded-8`, `-11`, `-17`, `-21` and `-25` only. The 51-level jar is that same
published artifact re-downgraded one step — precisely what upstream's own `downgradeApi()` task
does, and it needs no toolchains because the transform runs on the JDK running Gradle:

```sh
java -Djvmdg.javaApi="$API8" -cp "$JVMDG:$ASM" \
  xyz.wagyourtail.jvmdg.compile.ZipDowngrader 51 "$API8" \
  jvmdowngrader-java-api-2.0.1-downgraded-7.jar
```

where `$API8` is `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1:downgraded-8`, `$JVMDG`
is `xyz.wagyourtail.jvmdowngrader:jvmdowngrader:2.0.1`, and `$ASM` is the runtime classpath's ASM
jars. Building the API jar from upstream source is the one thing to avoid: `:java-api` compiles a
source set per JDK from 7 to 27 against Azul toolchains, so that path provisions some twenty JDKs on
every machine and CI job.

### Provenance

- Upstream: `unimined/JvmDowngrader` at `b739d657`, LGPL-2.1-or-later. The stubs are shaded into our
  jars, so the licence needs a source home to point at; the fork below adds the missing
  `downgraded-7` publication in six lines and holds nothing else.
- Fork: `kirjorjos/JvmDowngrader`, branch `java7-target`.

### Maintaining it

Replace this jar with upstream's when a `downgraded-7` is published, and re-read the downgrade
warnings to see whether the gaps that matter to us (`Stream`, `Optional`, `java.time`, the `Map` and
`Collection` defaults) have closed.

### Local patch in this copy

One upstream bug is fixed here, because it fires on ordinary code: 2.0.1's
Java8Downgrader.downgradeInterfaceAccesses collects the stub members to synthesise default-method
bridges without checking isMethod(), so a static interface field (CameraPlatform.NONE, for
instance) reaches Type.getArgumentTypes as Ljava/lang/Object; and the downgrade dies with
"StringIndexOutOfBoundsException: String index out of range: 18". 2.1.0 has the isMethod() guard
and 2.0.1 does not; this copy carries that guard, compiled from the tagged source with
javac --release 7 and dropped into the jar, so no JDK 7 toolchain is needed to rebuild it.
