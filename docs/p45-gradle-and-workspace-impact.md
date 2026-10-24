# P45: Change impact for Gradle builds and JavaScript workspaces

Status: 方案 4. Pull request impact analysis (P19) and module-selective runs only
understood Maven builds. Gradle multi-project builds and npm/pnpm/yarn workspace
monorepos always ran their whole suite, and their changed files showed up as
"outside the modules".

## Architecture Explanation

- **Graphs.** `BuildGraphs` reads two more kinds of build at the commit, without
  executing any build file:
  - **Gradle:**
    - Projects come from `settings.gradle(.kts)`, from `include(":a", ":b:c")` in
      both Groovy and Kotlin forms. Parent projects of nested paths are added, and
      commented-out includes are ignored.
    - Internal dependencies come from each `build.gradle(.kts)`:
      `project(":core")`, `project(path: ':core')`, and type-safe
      `projects.coreApi` accessors.
    - Build-wide files: the settings and root build scripts,
      `gradle.properties`, `gradle/` (version catalogs, wrapper), `buildSrc/`,
      `build-logic/` and `gradlew`.
  - **npm, yarn and pnpm workspaces:**
    - Packages come from the root `package.json` `workspaces` (array or
      `{packages}`, with `*` and `**` globs and `!` exclusions), or from
      `pnpm-workspace.yaml`.
    - Internal dependencies are package names in `dependencies`,
      `devDependencies`, `peerDependencies` and `optionalDependencies`.
    - Build-wide files: the root manifest, lockfiles, `pnpm-workspace.yaml`,
      `.npmrc`, `.yarnrc.yml` and root `tsconfig` files.
- **Impact.** `ChangeImpactAnalyzer` combines all builds in the repository. Maven
  keeps its exact previous behaviour; Gradle and workspaces then claim the files
  Maven did not.
  - Each affected module records its `buildSystem` and `coordinates`: the Maven
    groupId:artifactId, the Gradle project path, or the package name.
  - `buildSystem` names all builds, for example `maven+npm`.
  - `wholeBuildSystems` lists the builds whose build-wide files changed.
  - Tests are counted per build:
    - Gradle: `src/test/{java,kotlin,groovy}` classes named `*Test`, `*Tests`,
      `*Spec`, `*IT` or `Test*`;
    - workspaces: `*.test.*` and `*.spec.*` files, outside `node_modules`.
  - Impacts recorded before P45 still read as Maven.
- **Selection.** `ImpactSelection.decide(impact, suitesFile, system)` only looks at
  the suite's own build. A frontend package change does not run the backend
  suite, and the reverse holds too. As before, it runs in full whenever it is
  unsure:
  - the build is absent (`NOT_GRADLE`, `NOT_WORKSPACES`);
  - a build-wide or suites file changed;
  - the root module changed;
  - too many changes or modules.
- **New suite selections** in `.eahp/qa-suites.json`:

  | `selection` | Command rule | Selective run |
  | --- | --- | --- |
  | `gradle-projects` | `gradle`/`./gradlew`, last argument is the task | `./gradlew --no-daemon :core:test :web:test`; Gradle still builds what they depend on |
  | `npm-workspaces` | starts with `npm` | `npm test --workspace=packages/ui --workspace=apps/web` |
  | `pnpm-workspaces` | starts with `pnpm` | `pnpm --filter ./libs/a --filter ./apps/web -r test` |

  Module arguments are validated at the commit: Gradle paths must be projects of
  that build's settings, and workspace directories must contain a
  `package.json`.
- **Console.** The PR impact table shows each module's build and its
  coordinates. There are new full-run reasons ("not a Gradle build", "no
  workspaces"), translated into Chinese as well.

## Trade-off Analysis

- **Scanning, not evaluating, Gradle scripts.** Running a repository's build
  scripts to learn its structure would execute untrusted code. The scan covers
  the common declarations. Anything unusual (relocated `projectDir`, dynamic
  includes) is not seen, and changes to the scripts that could cause it
  (settings, build logic) always run the whole suite.
- **Dependents only, not dependencies.** Narrowed Gradle and workspace runs test
  the changed modules and everything that depends on them. Modules a change
  cannot reach are skipped. Maven keeps `-am` (P19), which also tests
  dependencies.

## Production Considerations

- No schema change: impacts are stored as JSON, and old records read as Maven.
- Selective Gradle runs need a task name as the last command argument, and
  workspace runs need the package manager's own workspace support (npm 7+,
  pnpm).

## Bilingual Interview Q&A

Q: How do you pick tests for a PR in a monorepo with several build systems?

中文：分别解析每种构建（Maven 的 pom、Gradle 的 settings 和 build 脚本、npm/pnpm 的 workspace），只扫描、不执行构建脚本。把改动文件归到所在模块，再加上依赖它的模块。每个套件只看自己那种构建：前端包的改动不会触发后端套件。只要改了构建级文件、根模块，或者改动过多，就完整运行。

English: Parse each build separately: Maven POMs, Gradle settings and build
scripts, npm/pnpm workspaces. Scan them, never execute them. Map each changed file
to its module and add the modules that depend on it. Each suite only considers its
own build, so a frontend package change does not run backend suites. Any change to
build-wide files or the root module, or too many changes, runs the full suite.

## Verification

- `BuildGraphImpactTest` (7):
  - Gradle: Kotlin and Groovy includes, nested projects, `project()` and
    accessor dependencies, ignored comments, test classes, selection by project
    path, and build-wide files (settings, root script, version catalog,
    `buildSrc`, properties);
  - npm workspaces with globs and exclusions, dependencies by package name, test
    files, lockfile changes;
  - pnpm workspace files;
  - Maven and npm side by side, kept separate;
  - accessor names and globs;
  - narrowed commands for Gradle, npm, pnpm and Maven.
- The existing `ChangeImpactAnalyzerTest` (7), `ImpactSelectionTest` (4),
  `PullRequestTriggerApiTest` (13) and `QaSuiteToolApiTest` (35, with the updated
  selection message) pass unchanged. Frontend: 27 tests pass.
- online-shop is a Maven build with a single non-workspace frontend, so its
  impact results are unchanged. The new graphs are verified by the unit tests
  above rather than by a live repository.
