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
