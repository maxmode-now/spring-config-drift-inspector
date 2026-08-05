# Spring Config Drift Inspector

Catch broken environment configs before they reach production — right inside IntelliJ.

`Spring Config Drift Inspector` compares every `application*.yml` / `application*.properties`
file in your project across profiles (`default`, `dev`, `stage`, `prod`, ...) and reports what a
code review alone will miss: keys that exist in `dev` but got dropped from `prod`, a value that's
an `int` in one environment and a `String` in another, credentials committed in plaintext, and
`${ENV_VAR}` references nothing actually supplies.

![Findings tab: severity counts, filters, and a masked credential](docs/images/findings.png)

## Why this exists

Two kinds of tools already cover parts of this problem, and both leave a gap:

- **Pure diff plugins** (e.g. Spring Profile Editor, Config Assistant) show you side-by-side
  files, but leave interpretation to you — they don't tell you *which* difference is a bug.
- **Full `@ConfigurationProperties` binders** (e.g. Spring Explyt) reimplement Spring's binding
  rules against your Java/Kotlin classes — powerful, but a large surface area with constant edge
  cases (relaxed binding, records, constructor binding, SpEL, custom converters).

This plugin sits deliberately in between: **static comparison with a lightweight metadata
contract check**, not a Binder reimplementation. It doesn't try to reproduce what Spring does at
runtime — it flags what's inconsistent, missing, or exposed in your files as written.

## What it catches

- ✅ **Missing keys** — set in some profiles, silently absent from others (with default-profile
  inheritance handled correctly, so a key in `application.yml` isn't flagged as "missing"
  everywhere else)
- ✅ **Shape drift** — the same key is a string in one environment and a number, list, or object
  in another
- ✅ **Exposed secrets** — passwords, tokens, API keys, private keys, and credentials embedded in
  URLs, detected by value *and* by key name — masked immediately at detection time, never stored
  or logged in plaintext
- ✅ **Unresolved placeholders** — `${DB_HOST}` with no default and nothing in the project to
  supply it; reported as INFO/WARNING, never as a hard error, because the plugin can't see your
  real deployment environment
- ✅ **Metadata contract checks** — cross-references `spring-configuration-metadata.json` for keys
  declared but never set, set but never declared, and simple type mismatches
- ✅ **Partial-overlay awareness** — a profile that only overrides a couple of keys
  (`SPRING_PROFILES_ACTIVE=prod,local`-style) isn't mistaken for an incomplete environment; the
  guess is shown as an INFO finding and can be overridden per profile in **Settings → Tools →
  Config Drift**
- ✅ **Relaxed-binding aware** — `driver-class-name`, `driverClassName`, and `DRIVER_CLASS_NAME`
  are recognized as the same property, so renamed-spelling isn't reported as drift

Every finding jumps straight to the exact key in the exact file (`Enter` or double-click), and can
be dismissed with `Delete`, `Alt+Enter`, or a right-click — dismissed findings move to a
**Suppressed** tab rather than disappearing, and stay shared with the team via
`.idea/configDrift.xml`. An exposed secret is the one finding that can't be dismissed: the fix is
to externalize the value, not to agree to stop hearing about it. Reports export as Markdown (for a
PR comment) or JSON (for a CI gate).

Findings also show up as live highlights in the editor, re-checked automatically a couple of
seconds after you save a config file — no need to keep re-running the analysis by hand:

![Inline highlights in application-dev.yml](docs/images/inline-inspection.png)

## What it deliberately does not do

Scope is a feature here, not a limitation to apologize for:

- No `@ConfigurationProperties` class analysis or Spring Binder reimplementation
- No SpEL, Vault, or Config Server resolution
- No live environment-variable lookup — the plugin only knows what's in your repository
- No automatic fixes — it reports, you decide

If real binding validation becomes worth the complexity later, it plugs into a dedicated
extension point (`BindingContractProvider`) without reshaping the engine.

## Requirements

IntelliJ Platform 2025.3 or later (IntelliJ IDEA, and any other IDE on the same platform).

## Installation

Not yet on the JetBrains Marketplace. Until then:

1. Download `spring-config-drift-inspector-<version>.zip` from
   [Releases](../../releases), or build it yourself (see below).
2. In your IDE: **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk...**
3. Select the zip and restart.

### Building from source

```bash
git clone https://github.com/maxmode-now/spring-config-drift-inspector.git
cd spring-config-drift-inspector
./gradlew buildPlugin
```

The packaged plugin appears at `build/distributions/*.zip`. Gradle will download a JDK 21
toolchain automatically if one isn't already installed.

## Usage

1. Open a project containing `application*.yml` / `.properties` files.
2. **Tools → Analyze Spring Config Drift** — required once, so there's something to show.
   After that, saving a config file re-runs the analysis automatically.
3. Review results in the **Config Drift** tool window, or as inline highlights in the editor:
   - **Findings** tab — every issue, sorted by severity, with a search box and a severity filter.
     `Enter`/double-click jumps to source (or opens Settings, for a finding that's about a profile
     rather than a key); `Delete` or right-click dismisses.
   - **Suppressed** tab — everything dismissed from Findings, restorable the same way.
   - **Key Matrix** tab — every key against every profile, searchable, with a toggle to show only
     keys missing from at least one profile:

     ![Key Matrix filtered to keys with a gap](docs/images/key-matrix.png)
4. Override the partial-overlay guess per profile in **Settings → Tools → Config Drift**, if a
   profile gets misclassified:

   ![Per-profile overlay override in Settings](docs/images/settings.png)
5. Export via the toolbar (**Copy Markdown Report** / **Copy JSON Report**) for a PR description
   or a CI check.

## Development

```bash
./gradlew test              # unit tests
./gradlew runIde             # launch a sandbox IDE with the plugin installed
./gradlew verifyPluginProjectConfiguration
```

A worked example with an intentional mix of every finding type lives in
[`testFixtures/sample-spring-project`](testFixtures/sample-spring-project), along with an
[expected-results checklist](testFixtures/sample-spring-project/EXPECTED.md).

## Contributing

Issues and pull requests are welcome. If you're proposing a new detection rule, include a
before/after example — false positives are taken seriously here, since a noisy inspection is one
users turn off.

## License

[MIT](LICENSE)
