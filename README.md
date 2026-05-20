# Omnissa Access Approvals

A Spring Boot 3.2 + React/TypeScript approval gateway for Omnissa Access (formerly VMware Workspace ONE Access). When a user requests access to an application, Omnissa Access POSTs a callout to this service; administrators approve or deny the request through a web UI. Decisions are sent back to Omnissa Access via the service client API.

---

## Prerequisites

- Docker and Docker Compose (for production deployment)
- A publicly reachable domain name pointing to your server (for Caddy/HTTPS mode)
- Java 17 and Maven 3.9+ (for local development only)
- An Omnissa Access tenant with administrator access

---

## Quick Start — Docker + Caddy (Recommended)

Caddy acts as a reverse proxy and obtains a TLS certificate automatically via Let's Encrypt.

**1. Clone the repository**

```bash
git clone https://github.com/squidlyman/Omnissa-Access-Approvals.git
cd Omnissa-Access-Approvals
```

**2. Create and edit the environment file**

```bash
cp .env.example .env
```

Fill in the required values. See [Configuration Reference](#configuration-reference) below.

**3. Start the stack**

```bash
docker compose up -d
```

The application will be available at `https://<APPROVAL_DOMAIN>`. Caddy handles certificate issuance on first startup — ensure ports 80 and 443 are open and DNS resolves to your server.

---

## Local Development

Uses an embedded H2 database and runs without Docker.

**1. Copy the local properties example**

```bash
cp config/application-local.properties.example config/application-local.properties
```

**2. Fill in the required values** in `config/application-local.properties`.

**3. Run the application**

```bash
mvn spring-boot:run -Dspring.config.additional-location=file:./config/ -Dspring.profiles.active=local
```

Open `http://localhost:8081`. The React frontend is built automatically by `frontend-maven-plugin` during the Maven build.

To iterate on the frontend without a full Maven build, run the Vite dev server separately after the first Maven build has populated `src/main/resources/static/`:

```bash
cd src/main/frontend
npm install
npm run dev
```

---

## Standalone TLS Mode

Use this when you have your own certificate and can't use Let's Encrypt:

```bash
# Generate a keystore from PEM files:
bash scripts/import-cert.sh
# Or generate a self-signed cert for testing:
bash scripts/gen-dev-cert.sh

docker compose -f docker-compose-standalone.yml up -d
```

Set `SSL_KEYSTORE_PASSWORD` in `.env` before starting.

---

## Configuration Reference

All variables are set in `.env` for Docker deployments, or in `config/application-local.properties` for local development.

### Omnissa Access Service Client

Allows the approval service to call back into Omnissa Access with approval decisions.

| Variable | Required | Description |
|---|---|---|
| `OMNISSA_BOOTSTRAP_URL` | Yes | Tenant hostname, e.g. `tenant.wss.workspaceone.com` |
| `OMNISSA_BOOTSTRAP_CLIENT_ID` | Yes | Service client ID from Omnissa Access |
| `OMNISSA_BOOTSTRAP_CLIENT_SECRET` | Yes | Service client secret |

### First-Run Admin Account

Creates a local administrator account on first startup if the user table is empty. Ignored on subsequent starts.

| Variable | Required | Description |
|---|---|---|
| `OMNISSA_BOOTSTRAP_ADMIN_USERNAME` | Yes | Username for the initial local admin |
| `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` | Yes | Password for the initial local admin |
| `OMNISSA_BOOTSTRAP_ADMIN_EMAIL` | No | Email address for the initial local admin |

### Admin OAuth2 Login (Optional)

Enables Omnissa Access as an OIDC identity provider for administrator login. If omitted, only local username/password login is available. Any user who successfully authenticates through Omnissa Access will be granted admin access — restrict access in the Omnissa Access client config accordingly.

| Variable | Required | Description |
|---|---|---|
| `OMNISSA_ADMIN_OAUTH_CLIENT_ID` | No | OIDC client ID from Omnissa Access |
| `OMNISSA_ADMIN_OAUTH_CLIENT_SECRET` | No | OIDC client secret |
| `OMNISSA_ADMIN_OAUTH_REDIRECT_URI` | No | Redirect URI registered in Omnissa Access. Default: `https://approvals.flaming.ws/login/oauth2/code/omnissa` |
| `OMNISSA_ADMIN_OAUTH_ISSUER_URI` | No | OIDC issuer URI, e.g. `https://tenant.wss.workspaceone.com/acs` |

### Email Notifications (Optional)

Outbound SMTP for approval decision notifications to requestors.

| Variable | Required | Description |
|---|---|---|
| `SPRING_MAIL_HOST` | No | SMTP server hostname |
| `SPRING_MAIL_PORT` | No | SMTP port. Default: `587` |
| `SPRING_MAIL_USERNAME` | No | SMTP authentication username |
| `SPRING_MAIL_PASSWORD` | No | SMTP authentication password |

### Deployment

| Variable | Mode | Description |
|---|---|---|
| `APPROVAL_DOMAIN` | Caddy | Public domain name, e.g. `approvals.flaming.ws` |
| `SSL_KEYSTORE_PASSWORD` | Standalone | Password for the PKCS12 keystore |

---

## Omnissa Access Setup

Two separate OAuth clients are required in your Omnissa Access tenant.

### Client 1 — Service Client (Approval Callout API)

This client authenticates the approval service when posting decisions back to Omnissa Access.

1. In the Omnissa Access console, create a new client:
   - **Client type:** Service
   - **Grant type:** Client Credentials
2. Copy the **Client ID** and **Client Secret** into `OMNISSA_BOOTSTRAP_CLIENT_ID` and `OMNISSA_BOOTSTRAP_CLIENT_SECRET`.
3. Set `OMNISSA_BOOTSTRAP_URL` to your tenant hostname.

**Configure the callout policy:**

In the Omnissa Access access policy where you want approval enforcement, configure the callout to POST to:

```
https://<APPROVAL_DOMAIN>/api/approvals/new
```

Omnissa Access will POST access requests here when a policy step requires approval. The approval service returns decisions asynchronously.

### Client 2 — OIDC Admin Login Client (Optional)

Allows administrators to log in using their Omnissa Access credentials.

1. Create a new client:
   - **Client type:** User Access Token (Confidential)
   - **Grant types:** Authorization Code + PKCE
   - **Scopes:** `openid`, `email`, `profile`
   - **Redirect URI:** `https://<APPROVAL_DOMAIN>/login/oauth2/code/omnissa`
2. Copy the **Client ID** and **Client Secret** into `OMNISSA_ADMIN_OAUTH_CLIENT_ID` and `OMNISSA_ADMIN_OAUTH_CLIENT_SECRET`.
3. Set `OMNISSA_ADMIN_OAUTH_ISSUER_URI` to `https://<your-tenant>/acs`.
4. Restrict which users can authenticate using this client in the Omnissa Access console — all authenticated users will receive admin access to the approval UI.

---

## First Login

### Local admin (default)

On first startup, if `OMNISSA_BOOTSTRAP_ADMIN_USERNAME` and `OMNISSA_BOOTSTRAP_ADMIN_PASSWORD` are set and the user table is empty, a local admin account is created automatically. Navigate to `https://<APPROVAL_DOMAIN>` and sign in with those credentials.

### Omnissa Access OAuth2

If the `OMNISSA_ADMIN_OAUTH_*` variables are configured, a **Sign in with Omnissa Access** button appears on the login page. Any user who authenticates successfully through that client is granted full admin access.

---

## Architecture

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5, Spring Security |
| Database | H2 embedded (file-backed, survives restarts) |
| Frontend | React, TypeScript, Vite, Tailwind CSS |
| Live updates | Server-Sent Events (SSE) |
| Email | Spring Mail + FreeMarker templates |
| Reverse proxy | Caddy (recommended) or embedded Tomcat TLS |
