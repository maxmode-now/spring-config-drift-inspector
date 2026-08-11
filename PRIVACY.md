# Privacy Policy — Spring Config Drift Inspector

**Last updated:** 11 August 2026  
**Vendor:** configdrift (`maxmode.now@gmail.com`)  
**Plugin:** [Spring Config Drift Inspector](https://plugins.jetbrains.com/plugin/33357-spring-config-drift-inspector)

This policy describes how the Spring Config Drift Inspector IntelliJ plugin (“the Plugin”) handles information when you use it in a JetBrains IDE.

## Summary

The Plugin runs **entirely on your machine**. It does **not** collect personal data, does **not** phone home, and does **not** upload your projects, configuration files, or findings to us or to any third-party service operated by the Plugin author.

## What the Plugin reads

When you run an analysis (or when the Plugin re-analyzes after a config file or folder change), it reads **local project files** that look like configuration, for example:

- Spring `application*.yml` / `application*.properties`
- `.env` / `.env.<profile>` files
- `docker-compose*.yml` / `compose*.yml`
- `spring-configuration-metadata.json` and project `@ConfigurationProperties` sources (Java/Kotlin), when present

Those files stay in your project and your IDE. The Plugin does not transmit them over the network.

## Secrets and sensitive values

Configuration files may contain passwords, tokens, or other secrets. The Plugin is designed so that:

- Likely secrets are **detected and masked as early as possible** during parsing.
- **Plaintext secret values are not retained** in findings, the tool window, editor highlights, logs written by the Plugin, or exported Markdown/JSON reports.
- Reports and UI may show a **masked** form (for example a short redacted hint), never the original secret text.
- For cross-profile comparison of redacted values, the Plugin may keep an **in-memory cryptographic digest** (SHA-256) and length/shape metadata — not the plaintext.

You remain responsible for what you commit to version control and for where you paste exported reports (for example into a pull request). The Plugin’s masking applies to its own UI and exports; it cannot control secrets that already exist in your repository files.

## What is stored on disk

The Plugin may write **project-level preferences** under your project’s IDE folder, typically:

- `.idea/configDrift.xml`

That file can contain:

- Manual profile classifications (complete environment / partial overlay / auto-detect overrides)
- Fingerprints of findings you have dismissed (suppressed)

It is intended as **project/team configuration**, not a personal profile, and may be shared if your team commits `.idea` settings. It does **not** store plaintext secrets.

Analysis results shown in the IDE session live in memory for the open project; they are not uploaded by the Plugin.

## Clipboard exports

If you use **Copy Markdown Report** or **Copy JSON Report**, the Plugin places already-masked report text on your local clipboard. Nothing is sent to the Plugin author. Where you paste that text is under your control.

## Network and telemetry

The Plugin itself:

- Does **not** include analytics or usage telemetry
- Does **not** open network connections to the author’s servers
- Does **not** require an account or login

Install, update, and Marketplace download statistics are handled by **JetBrains** under JetBrains’ own policies, not by this Plugin.

## Children

The Plugin is intended for software developers and is not directed at children. It does not knowingly collect personal information from anyone.

## Changes

If this policy changes in a material way, the “Last updated” date above will be revised and the updated text will be published in this repository (`PRIVACY.md`). Continued use of newer Plugin versions after that date constitutes acceptance of the updated policy for those versions.

## Contact

Questions about this policy: **maxmode.now@gmail.com**  
Source and issues: [github.com/maxmode-now/spring-config-drift-inspector](https://github.com/maxmode-now/spring-config-drift-inspector)
