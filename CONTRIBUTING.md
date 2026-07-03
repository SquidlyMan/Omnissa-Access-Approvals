# Contributing

Thanks for your interest in improving the Access Approval Tool for Omnissa.
This is a small community project — contributions of all sizes are welcome.

## Development Setup

Prerequisites: **Java 17**. Node.js/npm are downloaded automatically by the
Maven `frontend-maven-plugin` during the build — you do not need them
installed for a normal build.

```bash
# Full build (backend + frontend) and run on http://localhost:8081
./mvnw spring-boot:run
```

For local configuration (H2 database, test credentials), copy
`config/application-local.properties.example` to
`config/application-local.properties` and run with the `local` profile — see
the README's Local Development section.

### Frontend iteration

For fast frontend feedback, run the Vite dev server (after at least one Maven
build has populated `src/main/resources/static/`):

```bash
cd src/main/frontend
npm install
npm run dev
```

Vite proxies API calls to the Spring Boot backend, so keep the backend
running alongside it.

## Pull Requests

- Open an issue first for anything larger than a small fix, so the approach
  can be discussed before you invest time.
- Keep PRs focused — one logical change per PR.
- Make sure `./mvnw -B package` succeeds (this also builds the frontend).
- Describe what changed and why; include reproduction steps for bug fixes.
- Update the relevant docs (`README.md`, `docs/`) when behavior or
  configuration changes.

## Commit Style

Use short, conventional-commit-style subjects where practical:

```
feat: add expiry auto-reject rules
fix: accept messaging envelope content type on callout endpoint
docs: clarify OIDC issuer URI format
```

## Secrets

Never commit secrets — no client secrets, passwords, API tokens, private
keys, or real tenant hostnames in code, tests, docs, or fixtures. `.env`
files and `application-local.properties` are gitignored for this reason; use
placeholder values in examples.
