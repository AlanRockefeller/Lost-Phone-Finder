# Repository workflow

- Do all application development on the `test` branch. Switch to `test` before editing code.
- Push to `test`; open pull requests targeting `main` for CodeRabbit review. Alan merges on GitHub. Do not merge pull requests or push application changes to `main` unless Alan explicitly changes this workflow.
- Keep each initial review batch at most 100 changed files; if more files are necessary, stop after the first batch and leave the remainder for a later review.
- Commit source, tests, Gradle configuration/wrapper, Room schemas, verification tools and useful documentation. Exclude build output, caches, APKs, logs, device exports, local configuration, credentials and signing keys. Keep `.release-signing/` private.
- Use Gradle's resolved runtime dependency graph. Do not resurrect the cache-scanning APK packager. Audit non-minified APK class definitions with `tools/verify-apk-runtime.py`.
- Run checks appropriate to each change. For application/build changes, run both variants' automated tests, relevant lint/build tasks, and verify generated APKs when packaging or signing changes.
- Debug and release field-test APKs must use the same existing private signing certificate so they can replace each other without losing stored data. Never create or substitute a new signing key without an explicit request.
- The project is GPL-3.0-only; dependencies keep their own licenses.

- Write plain, natural prose in documentation, UI text and comments. Avoid em dashes and canned AI phrasing. Use concrete descriptions of behavior and checks.
