# Sample project — what the analysis should produce

Open this directory as a project in the `runIde` sandbox, then run
**Tools | Analyze Spring Config Drift**.

Profiles that should appear as columns: `default`, `dev`, `local`, `prod`, `stage`.
If `local` is missing, `---` document splitting is broken.

## Must-pass checks

| # | Check | Expected | If it fails |
| --- | --- | --- | --- |
| 1 | Tool window opens, table renders, 3 toolbar buttons visible | — | UI wiring / `plugin.xml` |
| 2 | `spring.jpa.hibernate.ddl-auto` vs dev's `ddlAuto` | ONE key, no MissingKey | `KeyNormalizer` — relaxed binding broken |
| 3 | dev `spring.datasource.password` | SecretExposure, shown as `••••••••••• (len=11)` | `SecretDetector` |
| 4 | prod `spring.datasource.password: ${DB_PASSWORD}` | **no finding at all** | false-positive source; would train users to ignore the tool |
| 5 | stage `${DB_PASSWORD:changeme}` | SecretExposure | placeholder-default handling |
| 6 | prod `app.api-key: AKIA...` | SecretExposure via `aws-access-key-id` | value-based rules not firing |
| 7 | `app.feature.timeout` | ShapeMismatch (dev/stage INTEGER vs prod STRING) | `ShapeMismatchAnalyzer` |
| 8 | `app.debug.verbose` | MissingKey for prod and stage — **not** for `default` | inheritance rule broken |
| 9 | prod `${DB_HOST}` | UnresolvedPlaceholder, severity **WARNING** not ERROR | severity policy |
| 10 | prod `app.mail.hostt` | SET_NOT_DECLARED | metadata contract |
| 11 | `app.unused-setting` | DECLARED_NOT_SET, severity INFO | metadata contract |
| 12 | Double-click any row | caret lands on the **key itself**, right file, right line | `SourceNavigator` / PSI offsets |
| 13 | Copy MARKDOWN / Copy JSON | clipboard has a report; **no plaintext secret anywhere in it** | masking guarantee |

## First run on this fixture (before fixes): 51 findings, ~35 noise

Recorded so the effect of later changes is measurable. 28 ERROR / 22 WARNING / 1 INFO, of which:
18 framework keys reported as undeclared, 8 MissingKey from the `local` overlay, 6 from lists
being split per element, 2 bogus TYPE_MISMATCH on list elements.

Five fixes were applied in response:

| Fix | Effect |
| --- | --- |
| SET_NOT_DECLARED restricted to namespaces the project's metadata declares | removes 18 |
| Scalar lists kept as one LIST key instead of `hosts[0]`, `hosts[1]` | removes 6, and makes list-vs-scalar drift detectable |
| Indexed keys compared against the element type, not the collection type | removes 2 |
| `UPPER_SNAKE` placeholders reported at INFO as a deploy checklist, not WARNING | stops warning on `password: ${DB_PASSWORD}`, the pattern the plugin should reward |
| Masking uses the committed secret, not the whole expression | `${DB_PASSWORD:changeme}` now reports len=8, not len=23 |

Confirmed after the fixes: exactly 24 findings — 19 ERROR, 2 WARNING, 3 INFO. All four noise
categories gone, and all 13 must-pass checks above verified (including the Key Matrix columns and
double-click landing on the key).

A sixth change then grouped MissingKey by key instead of by (key, profile) pair: a single typo'd
key had been producing one row per environment that correctly lacked the typo. That takes
MissingKey from 15 rows to 8 keys.

A seventh change then excluded partial-overlay profiles from missing-key comparison: `local` sets
1 key where other profiles set ~8, so every key absent from `default` looked missing there. The
exclusion is reported as an INFO finding rather than applied silently, because the same signature
also describes a real environment that is missing most of its configuration.

## Scalar-vs-object conflict — confirmed by a run

`app.cache` is a plain string in `dev` and a nested object (`host`, `port`) in `prod`. Flattening
hides this from per-key comparison, so it is detected separately by `StructuralConflict` and
reported as one ShapeMismatch (`dev=string, prod=map`) instead of MissingKey rows pointing in both
directions.

Confirmed end-to-end with `local` manually set to Partial overlay: **18 findings — 9 ERROR, 5
WARNING, 4 INFO**, exactly the predicted +1 ERROR / +3 WARNING over the 14-finding baseline below.
No MissingKey rows appeared for `app.cache` or `app.cache.*`.

## Baseline before that fixture addition — 14 findings

Regression target. Any change to these counts should be explained.

```
ERROR    3  SecretExposure         dev + stage password, prod AWS key
         1  ShapeMismatch          app.feature.timeout (integer vs string)
         4  MissingKey             app.apikey, app.debug.verbose, app.hosts, app.mail.hostt
WARNING  2  SET_NOT_DECLARED       app.apikey, app.mail.hostt (the typo)
INFO     1  DECLARED_NOT_SET       app.unused-setting
         2  EXTERNAL_ENVIRONMENT   DB_HOST, DB_PASSWORD deploy checklist
         1  OverlayProfileExcluded local
```

Down from 51 on the first run, with masking verified intact at every step.

## Settings override — all three paths verified

`Settings | Tools | Config Drift` overrides the overlay heuristic per profile. All three states
were exercised against this fixture:

| `local` set to | Total | What changes |
| --- | --- | --- |
| **Auto-detect** (default) | 14 | Heuristic guesses overlay (1 of ~8 keys); INFO says so and points at Settings |
| **Complete environment** | 17 | `app.feature.timeout` and the three `spring.datasource.*` keys reappear as MissingKey; the OverlayProfileExcluded INFO disappears |
| **Partial overlay** | 14 | Same counts as auto, but the finding carries `"manual": true` and the message reads "is marked as a partial overlay in Config Drift settings" rather than quoting key counts |

Settings persist to `.idea/configDrift.xml` — a project file, so the classification is shared with
the team rather than kept per-developer.

## Report output — both renderers verified

JSON and Markdown were each generated from this fixture and checked for the masking guarantee:
neither contained `devpassword`, `changeme`, or `AKIAIOSFODNN7EXAMPLE`. The Markdown report also
renders the key matrix with the `O` / `^` / `-` legend, and leaves the Key column as `—` for the
profile-level OverlayProfileExcluded finding that has no key.

## `.properties` multi-profile activation — fixed, not yet run-verified

`PropertiesConfigParser` took only `declaredProfiles()?.firstOrNull()`, so a `.properties` file
declaring `spring.config.activate.on-profile=dev|stage` silently dropped `stage` — all its entries
were attributed to `dev` alone. `YamlConfigParser` already handled this correctly via a
`retagTo(profile)` fan-out; the fix moves that helper to `RawPair.kt` so both parsers share it,
rather than `.properties` re-implementing (or mis-implementing) it separately.

`application-shared.properties` was added to check this without disturbing the baseline above:

```properties
spring.config.activate.on-profile=dev|stage
app.shared.flag=true
```

This can't be unit-tested — the parser needs real PSI. To verify by hand: open the **Key Matrix**
tab and confirm `app.shared.flag` shows `O` under **both** `dev` and `stage`, not just `dev`. Exact
finding-count deltas from this addition aren't pinned here, since they depend on whatever `local`'s
current Settings classification is at the time — the Key Matrix row is the unambiguous signal.

## Optional refinement, deliberately not applied

`app.mail.hostt` still yields two findings — the typo itself (SET_NOT_DECLARED) and the fact that
other profiles lack it (MissingKey). Suppressing MissingKey for keys already reported as undeclared
would cut two rows, but it assumes the project's metadata is complete. Projects that only annotate
some of their properties would lose a real signal, so this is left as a judgement call.

## `.env` support — not yet run-verified

`.env.staging` and `.env.production` were added at the project root (dotenv's own convention —
alongside `src/`, not inside `src/main/resources/`) to check the new `DotenvConfigParser` end to
end. Deltas from this addition aren't pinned into the finding-count sections above, since they
depend on the same Settings-classification and metadata state as everything else — check these
signals directly instead:

```
.env.staging:
  APP_NAME=sample-app
  DB_HOST=staging-db.internal
  DB_PASSWORD=devpassword123
  FEATURE_FLAG_BETA=true

.env.production:
  APP_NAME=sample-app
  DB_HOST=prod-db.internal
  DB_PASSWORD=${DB_PASSWORD}
```

To verify by hand:
1. **Key Matrix** shows `staging` and `production` as their own columns, distinct from Spring's
   existing `stage`/`prod` — confirms dotenv profiles don't collide with Spring ones just because
   the names are similar.
2. `DB_PASSWORD` in `.env.staging` (plaintext `devpassword123`) produces a SecretExposure, masked
   the same way as the Spring password findings above.
3. `DB_PASSWORD` in `.env.production` (`${DB_PASSWORD}`, no default) produces **no finding at
   all** — same "correctly externalized" rule the Spring fixture already exercises, now proven to
   apply to a format that never goes through `KeyNormalizer`.
4. `FEATURE_FLAG_BETA` (set in `.env.staging`, absent from `.env.production` and every Spring
   profile) produces a MissingKey finding — confirms cross-format comparison, not just
   within-format.
5. Double-click the `FEATURE_FLAG_BETA` or `DB_PASSWORD` finding: the caret lands on the correct
   line in the `.env.staging` file. This is the one that actually matters for this addition — it
   proves the new offset-based `ParseSupport.locationOf(psiFile, offset)` overload (no PSI element
   backing it, unlike Yaml/Properties) still produces a correct, clickable location.

## `docker-compose.yml` support — not yet run-verified

`docker-compose.yml` (minimal, just to check the bare-filename case is recognized as `default`),
`docker-compose.staging.yml`, and `docker-compose.production.yml` were added to check the new
`DockerComposeConfigParser` end to end. Deliberately reuses the `staging`/`production` profile
names the `.env` fixtures above already established, to also confirm two different formats
contribute to the *same* Key Matrix column when they name the same environment.

```
docker-compose.yml:
  services.web.environment: { APP_NAME: sample-app }

docker-compose.staging.yml:
  services.web.environment:
    DB_HOST: staging-db.internal
    DB_PASSWORD: devpassword123
    APP_PORT: "8080"
    LOG_LEVEL: debug
    DEBUG:                        # value-less — "pass through from host" in real Compose
  services.worker.environment:
    - QUEUE_URL=redis://staging:6379
    - WORKER_CONCURRENCY=2

docker-compose.production.yml:
  services.web.environment:
    DB_HOST: prod-db.internal
    DB_PASSWORD: ${DB_PASSWORD}
    APP_PORT: "8080"
    LOG_LEVEL: ${LOG_LEVEL:-info}
  services.worker.environment:
    - QUEUE_URL=redis://prod:6379
```

`WORKER_CONCURRENCY` is deliberately *not* also declared in the bare `docker-compose.yml`: a key
declared in the `default` profile is treated as inherited everywhere (the same rule that shields
Spring's `application.yml` keys from being flagged missing), so putting it there would silently
disable the exact MissingKey check this fixture exists to demonstrate.

To verify by hand:
1. **Key Matrix** shows keys as `web.DB_HOST`, `web.DB_PASSWORD`, `worker.QUEUE_URL`, etc. —
   service-qualified, not bare env var names — confirming `web`'s and `worker`'s variables don't
   collide as if they were the same key.
2. The `staging`/`production` columns are the *same* columns the `.env` fixtures already produced,
   not new duplicate ones — confirms cross-format profile merging by name.
3. `web.DB_PASSWORD` in staging (plaintext `devpassword123`) produces a masked SecretExposure;
   in production (`${DB_PASSWORD}`, no default) it produces **no finding at all**.
4. `web.LOG_LEVEL` in production (`${LOG_LEVEL:-info}`, bash-style default) produces **no**
   UnresolvedPlaceholder — confirms the `Placeholders.kt` `:-` extension actually reaches a real
   analysis, not just the unit test.
5. `worker.WORKER_CONCURRENCY` (staging only) and `web.DEBUG` (staging only, value-less) both
   produce MissingKey findings against production — confirms a value-less `environment:` entry is
   recorded as present-with-empty-value rather than silently dropped.
6. Double-click any docker-compose finding: the caret lands on the key inside the `environment:`
   block, in the correct file — this one uses real YAML PSI (unlike `.env`), so it's confirming the
   existing `ParseSupport.locationOf(psiFile, element)` path still works for a narrower walk than
   `YamlConfigParser`'s general one.

## `@ConfigurationProperties` PSI contract provider — not yet run-verified

`src/main/java/org/springframework/boot/context/properties/ConfigurationProperties.java` (a
minimal stand-in annotation — this fixture has no build file and no real Spring Boot dependency,
only the fully qualified name matters to the provider) and
`src/main/java/com/example/sample/MailProperties.java` were added to check
`ConfigurationPropertiesContractProvider` end to end. This is *not* in `additional-spring-configuration-metadata.json`
— the whole point of this fixture is that these properties are recognized without one.

```java
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {
    private String host;       // app.mail.host — leaf
    private Smtp smtp;         // recursed into — not a known leaf/container type
    private List<String> aliases; // app.mail.aliases — container leaf, not recursed into

    public static class Smtp {
        private int port;      // app.mail.smtp.port — leaf, nested one level
    }
}
```

To verify by hand:
1. Add `app.mail.hots: something` (typo'd `host`) to a profile — expect `SET_NOT_DECLARED`, since
   `app.mail` is now a declared namespace even with no metadata JSON entry for it.
2. Set `app.mail.smtp.port: not-a-number` in one profile — expect `TYPE_MISMATCH` against `int`,
   confirming the recursion into `Smtp` produced a real, one-level-deep contract.
3. Add `app.mail.aliases[0]: someone@example.com` — expect **no** `SET_NOT_DECLARED` on the indexed
   child path, confirming `List<String>` is treated as one open container contract (matching
   `MetadataContractAnalyzer`'s existing `isOpenContainer` exemption), not recursed into.
4. Add an unrelated `app.mail.smtp.host: x` (a property `Smtp` doesn't actually declare) — expect
   `SET_NOT_DECLARED`, confirming recursion produces a real per-nested-class contract rather than
   treating the whole `smtp` subtree as open.
5. This whole class was reachable without a real Spring Boot dependency on the fixture's
   classpath — confirms the provider resolves the annotation by qualified name, not by requiring
   the genuine library.

## Kotlin `@ConfigurationProperties` contract provider — not yet run-verified

`src/main/kotlin/com/example/sample/ServerProperties.kt` and `DatabaseSettings.kt` (deliberately
in a *separate* file) check `KotlinConfigurationPropertiesContractProvider` end to end, including
the one limitation that doesn't exist on the Java side: no semantic type resolution, so recursion
only follows a nested type declared in the *same file*.

```kotlin
@ConfigurationProperties(prefix = "app.server")
data class ServerProperties(
    val host: String,               // app.server.host — leaf
    val timeout: Duration,          // app.server.timeout — leaf (known value type)
    val advanced: Advanced,         // recursed into — Advanced is in this same file
    val database: DatabaseSettings, // NOT recursed into — declared in DatabaseSettings.kt
) {
    data class Advanced(val retries: Int, val backoffMs: Long)
}
```

To verify by hand:
1. Add `app.server.hots: something` (typo'd `host`) to a profile — expect `SET_NOT_DECLARED`,
   confirming the Kotlin provider declares the `app.server` namespace the same way the Java one
   declares `app.mail`.
2. Set `app.server.advanced.backoffMs: not-a-number` — expect `TYPE_MISMATCH` against `long`,
   confirming recursion into the same-file nested `Advanced` data class produced a real,
   correctly-typed contract from constructor properties (not fields — Kotlin has none to read).
3. Set `app.server.database.url: jdbc:...` — expect **`SET_NOT_DECLARED`**, not silence. This is
   the negative case that makes the same-file-only limitation concrete rather than only
   documented: `DatabaseSettings` isn't recursed into (it's in another file), so
   `app.server.database` itself is the only declared contract, and `app.server.database.url` is an
   undeclared child path exactly the way an ordinary unrecognized nested object would be.
4. Confirm `app.mail.*` (the Java fixture) and `app.server.*` (this one) both work in the same
   analysis run — the two providers are independent and additive, not an either/or.

Check #2 above is worth calling out specifically: it was originally written as if it already
worked, but didn't. `KotlinConfigurationPropertiesContractProvider` reported `declaredType` in
Kotlin's own spelling (`"Int"`, `"Long"`, `"List<String>"`), and `MetadataContractAnalyzer` matches
by exact string against a Java-spelled set (`"java.lang.Integer"`, `"java.util.List"`, ...) — so
`TYPE_MISMATCH` silently never fired for any Kotlin numeric/boolean property, and a Kotlin
`List`/`Map` property was never recognized as an open container, which would have made
`app.server.database.url` in check #3 misfire too. Fixed by having the provider translate the base
type name to its Java equivalent (`ConfigurationPropertyTypes.KOTLIN_TO_JAVA_BASE_TYPE_NAMES`)
before building the contract — caught by manual review before this was ever run-verified, which is
exactly why this section is titled "not yet run-verified" rather than a claim this actually passed.

## Settings table: an uncommitted combo-box edit could be lost on Apply/OK

`ConfigDriftConfigurable`'s per-profile classification table uses a `JComboBox` cell editor
(`comboEditor()`). Selecting a new value in an open dropdown doesn't write it into the table model
until the cell editor is explicitly stopped (Enter, Tab, clicking another cell, ...) — Swing holds
it in the editor component itself until then. `isModified()` and `apply()` both read
`tableModel.rowsSnapshot()` directly, with nothing forcing that commit first. If the user changed a
row's dropdown and then went straight for the dialog's Apply/OK button (a very ordinary sequence —
nothing tells a user to click elsewhere first), two different failures were possible depending on
timing:
- `isModified()` sees the *old* value, doesn't detect a change, and the Apply button can stay
  disabled for a change the user just made — closing the dialog with OK then discards it silently.
- Even where `apply()` does run, it would read the same stale snapshot and persist the previous
  classification instead of the one visibly selected in the dropdown.

Fixed by calling `com.intellij.ui.TableUtil.stopEditing(table)` at the top of both `isModified()`
and `apply()`, which is exactly what it exists for — committing any in-progress cell edit into the
model before either reads it.

To verify by hand: open **Settings | Tools | Config Drift** with at least one profile row (run
Analyze first), click a row's "Treat as" cell to open the combo dropdown, select a different value,
and — **without** clicking elsewhere or pressing Enter/Tab first — click **OK** directly. Reopen
Settings and confirm the new classification stuck.

## Manual Analyze could finish without opening the tool window

`ConfigDriftService.analyzeInBackgroundAndPublish(onSuccess)` gated `onSuccess()` behind
`sequenceGate.tryPublish(sequence)` — the same check that decides whether *this* run's results get
published rather than a newer concurrent one's. `AnalyzeConfigDriftAction`/the tool window's Rerun
button pass an `onSuccess` that activates the tool window, so whenever a save-triggered automatic
re-analysis happened to publish first (a plausible race: a `2.5s`-debounced auto re-analysis and a
manual "Tools | Analyze" started around the same time, in either order, can finish in either order),
the manual run's own tool-window activation was silently dropped along with its publish — even
though the user explicitly asked for an analysis and got one, just not the window they were
expecting to see it in.

Fixed by only gating `publish()`/the daemon restart on winning the race, and always calling
`onSuccess()` once the run's own analysis has completed — the tool window renders `lastReport`
either way, which is guaranteed to be at least as fresh regardless of which run actually published.

To verify by hand (the race is timing-dependent, so this needs deliberately provoking it): edit and
save a config file to queue an automatic re-analysis, then immediately trigger **Tools | Analyze
Spring Config Drift** before the ~2.5s debounce fires, so both are in flight together. Confirm the
tool window activates once the manual analysis completes, regardless of whether the automatic
re-analysis happens to publish around the same time.

## `.env` inline inspection highlight could span the entire file

`ConfigDriftInspection.checkFile()` passed `file.findElementAt(location.offset)` straight into
`createProblemDescriptor`, so the whole returned PSI element gets underlined. For YAML/Properties
that element is already key-sized. But `.env` has no PSI language of its own (see
`ParseSupport.locationOf`'s offset overload) — it parses as a single `PsiPlainTextFile` leaf
spanning the *entire file*, so `findElementAt` returns that one leaf for every offset in the file,
and the inline highlight would underline the whole `.env` file instead of the one line the finding
is actually about.

Fixed by computing a `rangeInElement` clipped to the offset's own line (via
`Document.getLineStartOffset`/`getLineEndOffset`, intersected with the element's own range,
shifted to be relative to the element) and passing it through the `createProblemDescriptor(element,
rangeInElement, ...)` overload. This is a no-op for the already line-sized YAML/Properties case and
bounds the highlight to one line for `.env`.

To verify by hand: open `.env.staging` in the sandbox editor with an on-the-fly analysis result
present, and confirm the `DB_PASSWORD` SecretExposure squiggly underlines only the
`DB_PASSWORD=devpassword123` line — not the whole file.

## Kotlin annotation prefix misread from a non-prefix single argument

`prefixOf()`'s `argumentText()` helper fell back to `annotation.valueArguments.singleOrNull()`
whenever an annotation had exactly one argument, on the assumption that a lone argument must be the
positional `prefix`/`value` (`@ConfigurationProperties("app.mail")`). But
`@ConfigurationProperties(ignoreUnknownFields = true)` also has exactly one argument — it's just
named `ignoreUnknownFields`, not a positional prefix. The old fallback matched it anyway and read
its expression text (`"true"`) as the prefix, so every property in that class would have been keyed
as `true.enabled` instead of the intended top-level `enabled`.

Fixed by requiring the single-argument fallback to also have no explicit name of its own
(`args.singleOrNull { it.getArgumentName() == null }`), so it only fires for an actual positional
argument.

`CacheProperties.kt` (new fixture file, `@ConfigurationProperties(ignoreUnknownFields = true)`, no
prefix) was added to check this by hand: with `enabled: Boolean` its only property, confirm the
resulting key is `enabled`, not `true.enabled` — check the **Key Matrix** or add `enabled: yes` to
a profile and confirm no `SET_NOT_DECLARED` fires for it (a `SET_NOT_DECLARED` on plain `enabled`
would mean the prefix bug is back).

## docker-compose list-form `environment:` values weren't normalized

`DockerComposeConfigParser.environmentEntries()`'s `YAMLSequence` branch (Compose's list form,
`- KEY=VALUE`) split each item on `=` by hand and used the two substrings as-is, only `.trim()`ing
the key half. The map form (`KEY: VALUE`) branch, by contrast, reads its value through
`YAMLScalar.textValue`, which the bundled YAML plugin already unquotes correctly — `KEY: "localhost"`
comes back as `localhost`. List-form values got neither the trim nor the unquoting: `- KEY="localhost"`
kept its literal `"` characters, and `- KEY= localhost` (space after `=`) kept its leading space,
since none of that is real YAML-level quoting — the whole `KEY=VALUE` text is one plain YAML
scalar, and the quotes/whitespace are just characters inside it once split by hand.

Concretely reproducible as a false `ShapeMismatch`: `ValueShapes.ofScalar` classifies `"8080"`
(quotes still attached) as `STRING` since it doesn't match the integer regex, but the equivalent
map-form `APP_PORT: "8080"` reads as unquoted `8080`, which does — `TYPE_MISMATCH`/`ShapeMismatch`
between two config files that mean the exact same numeric value, purely from which `environment:`
syntax each one happened to use.

Fixed by reusing [`DotenvParsing.unquote`][io.github.configdrift.parser.DotenvParsing] (now
`internal` rather than `private`, specifically for this reuse) on the trimmed value half — the same
`KEY=VALUE` env-line value syntax the `.env` parser already normalizes this same way, since Compose
list-form entries and dotenv lines are the same convention.

Not yet added to this fixture's tracked baseline counts: reproducing it would mean adding a
list-form/map-form pair of the same key with a quoted numeric value across two docker-compose
profiles, which shifts the numeric baselines tracked throughout this file (14 → 17 → ... above) in
a way that needs a real analysis run to re-pin correctly, not a guess. To verify by hand: add
`- APP_PORT="8080"` to one profile's `worker.environment` list and `APP_PORT: "8080"` to another
profile's `worker.environment` map, then confirm **no** ShapeMismatch/TYPE_MISMATCH appears between
them — both should read as the plain value `8080`.

## `@ConfigurationProperties` scoped to project sources, not the whole classpath

Both PSI providers (`ConfigurationPropertiesContractProvider`, `KotlinConfigurationPropertiesContractProvider`)
searched `AnnotatedElementsSearch.searchPsiClasses(annotationClass, GlobalSearchScope.allScope(project))`
— `allScope` includes every dependency jar, not just the project's own source. On a real Spring Boot
project this would have matched Spring Boot's own built-in `@ConfigurationProperties` classes inside
`spring-boot-autoconfigure.jar` (`ServerProperties`, `DataSourceProperties`, `JacksonProperties`, ...
there are dozens), most of whose fields no project's own config ever sets. Every one of those would
have surfaced as `DECLARED_NOT_SET`, exactly the kind of library-default noise both providers' own
class KDocs say they exist to avoid ("a project's own custom properties classes").

This fixture's own annotation stand-in (`ConfigurationProperties.java`, described above) happens to
live under the fixture's own `src/`, so it never exercised the bug — a fixture with a real library
dependency would be needed to reproduce it directly, which is why this was caught by re-reading the
scope argument against both providers' own documented intent rather than by a fixture run.

Fixed by switching the `searchPsiClasses` scope specifically to `GlobalSearchScope.projectScope(project)`
(project sources and tests, no libraries) in both providers, while leaving the earlier
`JavaPsiFacade.findClass(CONFIGURATION_PROPERTIES_FQN, GlobalSearchScope.allScope(project))` call
alone — the annotation class itself genuinely does live in a library, so only *that* lookup needs
`allScope`. `fileIndex.isInTestSourceContent(...)` still does its own job filtering out test sources,
since `projectScope` includes them.

To verify by hand once a fixture module has a real `spring-boot-autoconfigure` dependency on its
classpath: confirm none of Spring Boot's own built-in `@ConfigurationProperties` classes contribute
any `DECLARED_NOT_SET` findings, only classes under this project's own source roots.

## Cross-system comparison scoping — the bug this fixture caught

Adding `.env` and docker-compose support to a fixture that already had Spring config exposed a
real defect, and it took running the analysis on all three at once to see it: **29 ERROR findings
where 17 were real.** Every Spring-only key was reported missing from the `.env`/compose profiles
and vice versa:

```
'APP_NAME' is missing in dev, prod, stage but set in production, staging
'spring.datasource.url' is missing in production, staging but set in dev, prod, stage
'web.DB_HOST' is missing in dev, prod, stage but set in production, staging
```

None of those are defects. `dev` is a Spring profile with no `.env` file behind it, so `APP_NAME`
is not missing from it — the question doesn't apply. The engine had no notion of a key belonging
to a *config system*, so `MissingKeyAnalyzer` treated every profile as a candidate for every key.

Fixed by tagging each `ConfigEntry` with a `ConfigDomain` (`SPRING` / `DOTENV` / `DOCKER_COMPOSE`)
and scoping every comparison that infers **absence** to profiles that actually use the key's own
system — missing-key findings, the key matrix, and the metadata contract check. Comparisons that
only describe keys where they *are* set (shape drift, structural conflicts) are untouched: they
never claim a key is missing from anywhere, so they were never wrong.

Two latent bugs were fixed alongside it, both found by reasoning through the same scoping question
rather than by observation — neither had shown up yet in this fixture:
- A docker-compose service named `app` or `spring` produces keys like `app.DB_HOST`, whose
  *namespace* is one the metadata declares, so every one of its environment variables would have
  been reported as an undeclared Spring property. `MetadataContractAnalyzer` now considers Spring
  entries only.
- The partial-overlay heuristic measured every profile against every other, so a four-variable
  `.env.prod` sitting beside Spring profiles setting thirty properties each would be judged an
  overlay and dropped from comparison entirely, hiding every real gap in it. It now judges each
  config system's profiles against their own peers.

Expected after the fix — **17 ERROR**, and the check that matters is *which* ones:

```
MissingKey (8)     app.apikey, app.debug.verbose, app.hosts, app.mail.hots, app.shared.flag
                   FEATURE_FLAG_BETA   (.env: staging only)
                   web.DEBUG           (compose: staging only)
                   worker.WORKER_CONCURRENCY (compose: staging only)
SecretExposure (5) spring.datasource.password ×2 (dev, stage), app.apikey (aws),
                   DB_PASSWORD (.env staging), web.DB_PASSWORD (compose staging)
ShapeMismatch (3)  app.cache, app.feature.timeout, app.mail.port
TYPE_MISMATCH (1)  app.mail.port
```

The three cross-format MissingKey rows are the fixture's whole point: they are real gaps *within*
one system, proving the scoping narrowed the comparison rather than disabling it.

In the **Key Matrix**, cells that were wrongly `-` now render `~` ("not applicable — different
config system"). The "only keys missing somewhere" filter matches `-` alone, so those rows
correctly drop out of it.
