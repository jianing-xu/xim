# Repository Guidelines

## Project Structure & Module Organization

XIM is a single-instance messaging application with Netty TCP and WebSocket transports.

- `backend/src/main/java/cn/xjn/xim/`: server/client handlers, authentication, protocol codecs, and storage.
- `backend/src/main/resources/db/migration/`: ordered SQL migrations (`V6__user_directory.sql`); backend tests mirror packages under `backend/src/test/java/`.
- `frontend/src/`: React components, chat state, transport, styles, and colocated unit tests. Static assets live in `frontend/public/`; browser tests in `frontend/e2e/`.
- `protocol/xim.proto`: shared Protobuf schema; `protocol/golden-vectors.json` contains interoperability fixtures.
- `scripts/` and `deployment/`: release, backup, and hosting tools. `docs/` contains design references and screenshots.

## Build, Test, and Development Commands

Use JDK 25, Maven 3.9+, and Node.js 20.19+ or 22.12+.

- `./backend/run.sh -Dxim.web.cookieSecure=false`: start the backend for local HTTP development (TCP 8000, HTTP/WebSocket 8080).
- In `frontend/`, run `npm ci`, then `npm run dev`: install locked dependencies and start Vite on port 5173.
- `mvn -f backend/pom.xml clean verify` from the root: compile Java, run unit/integration tests, and generate JaCoCo coverage.
- In `frontend/`, `npm test`, `npm run typecheck`, and `npm run build`: run Vitest, check TypeScript, and build production assets.
- In `frontend/`, `npm run e2e:prepare && npm run test:e2e`: prepare release artifacts and run Playwright using local Chrome.
- `./scripts/build-release.sh`: validate and package both applications.

## Coding Style & Naming Conventions

Match existing code: four-space Java indentation; two-space TypeScript/TSX indentation, single quotes, and semicolons. Use PascalCase for classes/components, camelCase for methods/variables, and descriptive kebab-case TypeScript utility filenames. TypeScript uses strict checking; no formatter or linter is configured.

Edit `protocol/xim.proto`, then regenerate through Maven and frontend npm hooks. Never hand-edit or commit generated bindings in `frontend/src/generated/` or `backend/target/`.

## Testing Guidelines

Use JUnit 4 (`*Test.java` for Surefire, `*IT.java` for Failsafe), Vitest (`*.test.ts`/`*.test.tsx`), and Playwright (`*.spec.ts`). Add regression coverage for changed behavior. JaCoCo reports coverage without an enforced percentage threshold. PostgreSQL tests require `XIM_TEST_PG_URL`, `XIM_TEST_PG_USER`, and `XIM_TEST_PG_PASSWORD`; otherwise they skip.

## Commit & Pull Request Guidelines

Follow recent Conventional Commit subjects: `feat(protocol): ...`, `feat(ui): ...`, or `docs: ...`. Keep commits focused. PRs should explain behavior changes, link relevant issues, list validation results and skipped checks, and include screenshots for UI changes. Document protocol, migration, or deployment impacts.

## Security & Configuration

Keep credentials, databases, and local environment files out of Git. Disable secure cookies only for local HTTP; production requires HTTPS/WSS. Use `scripts/sqlite-backup.py` for live SQLite backups.
