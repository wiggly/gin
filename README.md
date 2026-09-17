# wiggly-gin

A multiplayer game server for Gin Rummy

## Modules

| Module            | Path             | Role                                                   |
| ----------------- | ---------------- | ------------------------------------------------------ |
| `wiggly-gin-core` | `modules/core`   | Domain and ports — pure, no infrastructure             |
| `wiggly-gin-server` | `modules/server` | Driving adapter — exposes the game over HTTP           |

### Where a file goes

The packages are named after the role a file plays in the hexagon, so the role is readable from the
path.

```
modules/core/src/main/scala/wiggly/gin/core/
  domain/   the rules of the game: no F[_], no library but cats-core
  port/     traits in F[_] that the domain owns, inbound and outbound together
  service/  drives the domain to satisfy an inbound port

modules/server/src/main/scala/wiggly/gin/server/
  adapter/
    http/   inbound: the routes that call into the application
    memory/ outbound: an implementation of a core port
  config/         the runtime shell
  HttpServer.scala
  Main.scala      the composition root, the only place that knows every adapter
```

Three rules keep it honest:

1. `core/domain` names no technology. A `GameRepository[F]` trait is domain code; a `Transactor` is
   not.
2. A port is declared in `core/port` and implemented under `server/adapter/<technology>`, which is
   the only place that technology is named.
3. `Main`, `HttpServer` and `config` are not adapters. They are the shell that reads the
   configuration, builds the adapters and runs them. Everything depends on the shell and the shell
   depends on everything, so nothing else may.

`core/port`, `core/service` and `server/adapter/memory` arrive with step 5 of the
[roadmap](docs/ROADMAP.md); the rest of the tree exists today.

Ports are not split into inbound and outbound packages. Which direction a port faces is clear from
who implements it, and there will only ever be a handful.

## Running the server

```bash
sbt server/run
curl http://localhost:8080/health
```

### Configuration

All configuration comes from the environment, with defaults that work unchanged in a container.

| Variable                    | Default      | Meaning                                          |
| --------------------------- | ------------ | ------------------------------------------------ |
| `GIN_HTTP_HOST`             | `0.0.0.0`    | Interface to bind                                |
| `GIN_HTTP_PORT`             | `8080`       | Port to bind                                     |
| `GIN_HTTP_SHUTDOWN_TIMEOUT` | `30 seconds` | Grace period for in-flight requests on shutdown  |
| `GIN_LOG_LEVEL`             | `INFO`       | Root log level                                   |

An unparseable value fails startup rather than silently falling back to the default.

The defaults and the environment variables that override them live in
`modules/server/src/main/resources/application.conf`; they are read with
[pureconfig](https://pureconfig.github.io/).

## Endpoints

| Endpoint   | Purpose                                                          |
| ---------- | ---------------------------------------------------------------- |
| `/health`  | Liveness probe: `200 {"status":"ok"}`                            |
| `/api/v1/` | Prefix under which the game's routes are mounted                 |

Every response carries a JSON body, including `404` and `500`, so a client can parse them all the
same way.

## Tests

```bash
sbt test            # all modules
sbt server/test     # one module
sbt scalafmtAll     # format; scalafmtCheckAll to verify without writing
```

Tests are [weaver](https://typelevel.org/weaver-test/) suites, property-based by default via
`weaver-scalacheck`. Conventions:

- **Suites are objects named `*Suite`**, matching the class each one extends (`SimpleIOSuite`).
- **Generators live in `modules/core/src/test/scala/wiggly/gin/gen/`** and reach other modules
  through the `test->test` dependency in `build.sbt`, so domain generators are written once.
- **Prefer passing an explicit `Gen` to `forall` over an implicit `Arbitrary`.** For a constrained
  domain type there is rarely one obvious distribution — any ten cards, a hand holding melds, and a
  hand at the knock boundary are all "a hand" — and an implicit instance hides which one a test
  actually ran against.
- **Properties for invariants, `pureTest` for rulebook examples.** Specific scoring cases from the
  rules are worth pinning literally rather than deriving.
- **Keep generators narrow.** weaver's checkers do not shrink: a counterexample is reported exactly
  as generated. The failure output does include a seed that reproduces it, e.g.
  `forall.withConfig(checkConfig.withInitialSeed(...))`.
