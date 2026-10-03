# Plan: the container and the build that checks it

A working plan for step 6 of the [roadmap](ROADMAP.md), which is the 12-factor goal stated in
`CLAUDE.md` and the CI pipeline the same file has so far left out on purpose.

Nothing in this step changes a line of Scala. It is the build, one workflow file, and the
documents, and the point of it is that the two checks a person currently runs from memory are run
by a machine instead.

## Decisions settled up front

| Decision | Choice | Why |
| --- | --- | --- |
| How the image is built | `sbt-native-packager` 1.12.0, with `JavaAppPackaging` and `DockerPlugin` | The plugin the roadmap already named. It writes the Dockerfile, the start script and the non-root user, so none of those is a file anybody has to keep in step with the build. |
| The base image | `eclipse-temurin:25-jre` | The JDK the project builds on, as a JRE. 490MB against the 306MB of the Alpine variant, which was the deliberate trade: glibc rather than musl, and the same libc as the machine anybody debugs on. |
| Heap | `-XX:MaxRAMPercentage=75` baked into the start script | A JVM in a container sizes its heap from the container's limit only if told to in percentage terms. Without it the default is a fraction of the host's memory, which is the wrong number and is wrong silently. `JAVA_OPTS` still overrides it. |
| `HEALTHCHECK` | Not in the image | `/health` is there and documented, and an orchestrator defines its own probe. Baking one in would mean adding a package to the image for the sole purpose of making an HTTP request. |
| What CI runs | The gate, then `coverageAll`, then the image, then a smoke test of the image | The first two are what `CLAUDE.md` asks a person to run. The third is what stops the packaging drifting, and the fourth is what stops it producing an image that builds and cannot start. |
| What CI publishes | Nothing | No registry to decide on, no credentials, and no tagging scheme to invent before anybody needs one. Publishing is a later step in the same workflow. |
| When CI runs | Pushes to `main` and every pull request | A pull request is where the gate earns its keep. Running on every branch push as well would only duplicate it. |
| Action versions | `actions/checkout@v7`, `actions/setup-java@v6`, `sbt/setup-sbt@v1` | Checked against their releases rather than remembered. sbt is no longer on the runner image, which is why the third one is there at all. |

## 1. The image

```
project/plugins.sbt
build.sbt
```

```scala
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.12.0")
```

The `server` project enables the two plugins and gains the settings that describe the image:

```scala
.enablePlugins(JavaAppPackaging, DockerPlugin)
.settings(
  Docker / packageName := "wiggly-gin",
  dockerBaseImage      := "eclipse-temurin:25-jre",
  dockerExposedPorts   := Seq(8080),
  dockerUpdateLatest   := true,
  Universal / javaOptions += "-J-XX:MaxRAMPercentage=75"
)
```

The port matches the default in `application.conf`, so an image run with no environment at all
listens where the Dockerfile says it does. Everything else about the running process is already
environment-driven, which is what makes this step small.

**Checks.** `sbt server/Docker/publishLocal` builds it. Then the image is run with nothing set, and
`/health` answers; and run with `GIN_HTTP_PORT` set to something else, and it answers there
instead, which is the check that the configuration is read from the environment rather than baked
in at build time. Then a whole game is played through the container: created, joined, a move made
and the event stream watched, because an image that serves `/health` and nothing else is an image
that has not been tested.

## 2. The workflow

```
.github/workflows/ci.yml
```

One job on `ubuntu-latest`, triggered by a push to `main` and by any pull request:

1. `actions/checkout@v7`
2. `actions/setup-java@v6` with Temurin 25 and `cache: sbt`
3. `sbt/setup-sbt@v1`
4. `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
5. `sbt coverageAll`
6. `sbt server/Docker/publishLocal`
7. Start the image, wait for `/health`, stop it

Step 4 is the gate from `CLAUDE.md`, character for character, so the two cannot disagree about what
the gate is. Step 5 is separate because it is slower and because it rebuilds from clean, and
because a coverage floor that is only enforced when somebody remembers to run it is not a floor.

**What this plan cannot check.** A workflow file cannot be run from here. Every command in it is
run locally first, and the file itself is parsed, but the first real evidence that the workflow
works will be the first push. That is worth saying rather than implying otherwise.

## 3. The documents

`README.md` gains a section on building and running the container, next to the one on running the
server with sbt. `ROADMAP.md` gains this as step 6, loses the packaging line from its "Later" list,
and loses the sentence in "Settled" that says CI is absent by choice. `CLAUDE.md` loses the same
claim from its Compiler options section: the gate is still worth running by hand before pushing,
but it is no longer the only thing that runs it.

## Order of work

Three commits, each green on its own:

1. The image: the plugin, the settings, and the checks above run by hand.
2. The workflow.
3. The documents.

The gate from `CLAUDE.md` still applies to each of them:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

## Deliberately left out

No registry and no published image, which is the decision above rather than an omission.

No image signing, no SBOM and no vulnerability scan. All three belong with publishing, because
they are claims about an artefact somebody else is going to pull, and nobody is pulling this one
yet.

No release versioning. The build is `0.1.0-SNAPSHOT` and the image is tagged from it, which is
honest about what it is. A tagging scheme is part of deciding to publish.

No multi-architecture build. The image is built for whatever the builder is, which is what a
single developer and a single CI runner both want. Buildx and a second architecture arrive with a
registry to put them in.
