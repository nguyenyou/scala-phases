# Repository Guidelines

## Project Structure & Module Organization
Scala sources live under `www/src/www`, grouped by packages such as `phases` for transformation logic and `logging` for structured output. `Main.scala` coordinates phase execution and should remain the single orchestration entry point. Shared test fixtures belong in `www/test/src`, mirroring the package layout of the code they cover. Static assets (`index.html`, `main.js`, `style.css`, plus `public/`) back the Vite demo served during local development. Build settings and dependency versions are centralized in `build.mill` and `.scalafmt.conf`; update those files instead of scattering configuration overrides.

## Build, Test, and Development Commands
- `./mill www.compile` — compile the Scala 3 module and fail fast on configuration drift.
- `./mill www.test` — run the uTest suite in `www/test/src`.
- `yarn dev` — launch the Vite dev server for the static demo at `index.html`.
- `yarn build` — produce an optimized Vite bundle into `dist/` (clean it before checking in).
- `yarn fmt` — execute the scalafmt mill task (`mill.scalalib.scalafmt/`) across the Scala tree.
Run `yarn install` after dependency bumps so Vite and tooling stay in sync with `package.json`.

## Coding Style & Naming Conventions
Follow the Scala 3 defaults enforced by scalafmt (`maxColumn = 120`, two-space indents, aligned operators). Name classes and traits in PascalCase, companion objects to match, and packages in lowercase. Phase implementations should stay in `phases/` and use descriptive `*Phase.scala` filenames; log-related utilities belong in `logging/`. Avoid ad-hoc println debugging—prefer the structured logger in `logging/`.

## Testing Guidelines
Tests use uTest (`www.test`). Name suites `*Tests.scala` and mirror the package of the unit under test. Favor focused tests that assert phase outputs and logged metadata. Run `./mill www.test` before pushes; add new suites for every non-trivial phase or logger change. If a test relies on async logging, document the assumptions in the test body.

## Commit & Pull Request Guidelines
Commits should stay small, scoped, and written in the imperative mood. The history shows emoji-prefixed summaries like `🚢 ship it`; feel free to keep that convention but ensure the trailing verb phrase is meaningful. Reference tickets or issues in the body when relevant. Pull requests need: a brief problem statement, a bullet list of changes, test evidence (`./mill www.test` output or screenshots for Vite), and notes about follow-up work. Request review from a peer familiar with the touched phase or logging package.
