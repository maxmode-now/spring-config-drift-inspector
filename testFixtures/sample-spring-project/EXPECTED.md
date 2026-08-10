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
