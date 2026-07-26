import { useAuth } from '../hooks/useAuth'
import { canAdminister } from '../lib/permissions'

interface HelpSectionProps {
  title: string
  children: React.ReactNode
}

function HelpSection({ title, children }: HelpSectionProps) {
  return (
    <section className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6">
      <h2 className="font-semibold text-gray-800 text-lg mb-3">{title}</h2>
      <div className="text-sm text-gray-600 space-y-3">{children}</div>
    </section>
  )
}

function EnvVar({ name }: { name: string }) {
  return <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">{name}</code>
}

function Code({ children }: { children: React.ReactNode }) {
  return <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5 break-all">{children}</code>
}

function CodeBlock({ children }: { children: string }) {
  return (
    <div className="overflow-x-auto">
      <pre className="text-xs bg-gray-100 text-gray-800 rounded-lg px-3 py-2 whitespace-pre">{children}</pre>
    </div>
  )
}

interface ConfigVar {
  name: string
  def: string
  purpose: string
}

const CONFIG_VARS: ConfigVar[] = [
  { name: 'OMNISSA_BOOTSTRAP_URL', def: '—', purpose: 'Omnissa Access tenant URL used by the service client (approvals API and connectivity check).' },
  { name: 'OMNISSA_BOOTSTRAP_CLIENT_ID', def: '—', purpose: 'Service client ID used to call the Access API.' },
  { name: 'OMNISSA_BOOTSTRAP_CLIENT_SECRET', def: '—', purpose: 'Shared secret of the service client.' },
  { name: 'OMNISSA_BOOTSTRAP_ADMIN_USERNAME', def: '—', purpose: 'First-run local admin username (created only when the user table is empty).' },
  { name: 'OMNISSA_BOOTSTRAP_ADMIN_PASSWORD', def: '—', purpose: 'First-run local admin password.' },
  { name: 'OMNISSA_BOOTSTRAP_ADMIN_EMAIL', def: '—', purpose: 'First-run local admin email address.' },
  { name: 'OMNISSA_ADMIN_OAUTH_CLIENT_ID', def: '—', purpose: 'OAuth2 client ID for "Sign in with Omnissa Access" admin SSO.' },
  { name: 'OMNISSA_ADMIN_OAUTH_CLIENT_SECRET', def: '—', purpose: 'Shared secret of the admin SSO client.' },
  { name: 'OMNISSA_ADMIN_OAUTH_REDIRECT_URI', def: '—', purpose: 'Redirect URI; must exactly match the URI registered on the Access client.' },
  { name: 'OMNISSA_ADMIN_OAUTH_ISSUER_URI', def: '—', purpose: 'Tenant OIDC issuer, e.g. https://<tenant>/SAAS/auth.' },
  { name: 'OMNISSA_ADMIN_OAUTH_DISABLE_CONSENT', def: 'false', purpose: 'true disables the Access client user-consent prompt at startup (needs admin rights).' },
  { name: 'OMNISSA_AUTH_LOCAL_LOGIN_DISABLED', def: 'false', purpose: 'true hides the local username/password form; OAuth-only sign-in.' },
  { name: 'OMNISSA_API_USERNAME', def: '—', purpose: 'Enables HTTP Basic authentication on the approval callout API (username).' },
  { name: 'OMNISSA_API_PASSWORD', def: '—', purpose: 'Password for callout API Basic authentication.' },
  { name: 'OMNISSA_API_RATE_LIMIT', def: '60', purpose: 'Callout requests per minute per source IP; 0 disables rate limiting.' },
  { name: 'SERVER_PORT', def: '8081', purpose: 'HTTP listen port of the application.' },
  { name: 'SPRING_MAIL_HOST', def: '—', purpose: 'SMTP server for email notifications.' },
  { name: 'SPRING_MAIL_PORT', def: '—', purpose: 'SMTP server port.' },
  { name: 'SPRING_MAIL_USERNAME', def: '—', purpose: 'SMTP username.' },
  { name: 'SPRING_MAIL_PASSWORD', def: '—', purpose: 'SMTP password.' },
  {
    name: 'SPRING_MAIL_FROM',
    def: 'no-reply@example.com',
    purpose: 'Sender (From) address for requester emails — must be accepted by the relay.',
  },
  { name: 'WEBHOOK_URL', def: '—', purpose: 'POST a notification to this URL for each new access request and each decision.' },
  { name: 'WEBHOOK_FORMAT', def: 'generic', purpose: 'Webhook payload format: generic, slack, or teams.' },
  { name: 'WEBHOOK_NOTIFY_LIFECYCLE', def: 'true', purpose: 'Also notify when time-bound access is revoked at expiry and when the app becomes requestable again. false = audit trail and web UI only.' },
  { name: 'APP_BASE_URL', def: '—', purpose: 'Public URL of this tool (e.g. https://approvals.example.com). Required for actionable Teams cards — their deep links are built off-request, so the URL cannot be read from forwarded headers.' },
  { name: 'SLACK_ACTIONABLE', def: 'false', purpose: 'true posts an interactive Slack message with an access-duration menu and Approve / Reject / Reject-and-Block buttons. Requires WEBHOOK_FORMAT=slack.' },
  { name: 'SLACK_SIGNING_SECRET', def: '—', purpose: 'Slack app signing secret. Verifies inbound button clicks at POST /api/slack/interactions (HMAC-SHA256, 5-minute replay window). Required — without it every click is rejected.' },
  { name: 'SLACK_APPROVER_MAP', def: '—', purpose: 'Who may decide from Slack: comma-separated slackUserId:appIdentity pairs. A valid signature only proves the workspace, so unlisted users are rejected — channel membership never grants approval rights.' },
  { name: 'TEAMS_ACTIONABLE', def: 'false', purpose: 'true posts a Microsoft Teams Adaptive Card whose buttons open the request with the decision pre-selected. Requires WEBHOOK_FORMAT=teams and APP_BASE_URL.' },
  { name: 'SYSLOG_HOST', def: '—', purpose: 'Forward application logs to this syslog server.' },
  { name: 'SYSLOG_PORT', def: '514', purpose: 'Syslog port number.' },
  { name: 'SYSLOG_PROTOCOL', def: 'udp', purpose: 'Syslog transport: udp, tcp, or tls.' },
  { name: 'SYSLOG_CLIENT_CERT_PEM', def: '—', purpose: 'Client certificate PEM (pasted inline) for TLS syslog.' },
  { name: 'SYSLOG_CLIENT_KEY_PEM', def: '—', purpose: 'Client private key PEM (PKCS#8, pasted inline).' },
  { name: 'SYSLOG_CA_PEM', def: '—', purpose: 'CA certificate PEM (pasted inline) for a private CA.' },
  { name: 'SYSLOG_CLIENT_CERT_FILE', def: '—', purpose: 'Path to a client certificate file, e.g. /app/data/certs/client.pem.' },
  { name: 'SYSLOG_CLIENT_KEY_FILE', def: '—', purpose: 'Path to a client private key file (PKCS#8).' },
  { name: 'SYSLOG_CA_FILE', def: '—', purpose: 'Path to a CA certificate file.' },
]

export default function HelpPage() {
  const { user } = useAuth()
  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-6">Help</h1>

      <div className="space-y-6 max-w-4xl">
        <HelpSection title="Overview">
          <p>
            The Access Approval Tool for Omnissa receives approval callouts from Omnissa Access whenever
            a user requests an application. Requests appear in this tool's queue, where
            administrators review and approve or reject them. Each decision is sent back to
            Omnissa Access, which then grants or withholds the application for the user.
          </p>
        </HelpSection>

        <HelpSection title="Configuring Omnissa Access Approvals">
          <p>
            In the Omnissa Access console, go to <span className="font-medium text-gray-800">Settings &gt; Approvals</span> and
            configure the following:
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>Set <span className="font-medium text-gray-800">Enable Approvals</span> to on.</li>
            <li>Set <span className="font-medium text-gray-800">Approval Engine</span> to <span className="font-medium text-gray-800">REST API</span>.</li>
            <li>
              Set <span className="font-medium text-gray-800">URI</span> to{' '}
              <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">https://&lt;your-host&gt;/api/approvals/new</code>.
            </li>
            <li>
              The <span className="font-medium text-gray-800">Username</span> and{' '}
              <span className="font-medium text-gray-800">Password</span> fields are only required if API
              Basic authentication is enabled on this tool (see Security Options below).
            </li>
          </ul>
        </HelpSection>

        <HelpSection title="Application Configuration Notes">
          <ul className="list-disc pl-5 space-y-2">
            <li>
              An application to be locked behind approval must be added or edited with the{' '}
              <span className="font-medium text-gray-800">License Approval Required</span> setting enabled.
              License Pricing and Type — including cost and number of licenses — are all optional.
            </li>
            <li>
              Once an application has License Approval Required enabled, adding an application
              assignment with deployment type <span className="font-medium text-gray-800">User-Activated</span> lets
              the user request the application from the catalog: they click the app to open its
              details, then click the <span className="font-medium text-gray-800">REQUEST</span> button. The app
              then shows <span className="font-medium text-gray-800">PENDING</span>, and admins must approve or
              decline the request in this tool. If approved, the application becomes available for
              launch. If an application request is declined, the request is listed as Rejected in
              this tool, the Pending state for the end user is dropped, and the application returns
              to a locked option in the Access catalog. The user can request the resource again.
            </li>
            <li>
              When an application assignment is set to <span className="font-medium text-gray-800">Automatic</span>,
              the approval request is sent to this tool automatically. Once approved, the
              application appears for the user.
            </li>
            <li>
              When the assignment type is set to <span className="font-medium text-gray-800">Excluded</span>, the
              application is deactivated, hidden from the user's catalog, and appears in this
              tool's Deactivated list.
            </li>
            <li>
              If a decision cannot be delivered because the request no longer exists in Omnissa
              Access, the request is moved to the{' '}
              <span className="font-medium text-gray-800">Deactivated</span> list with an{' '}
              <span className="font-medium text-gray-800">Expired</span> badge. The audit trail
              records a <Code>decision-undeliverable</Code> event and the webhook (if configured)
              emits <Code>request.expired</Code>. A transient Access outage does NOT expire the
              request — it stays in Awaiting Review and the decision can be retried.
            </li>
          </ul>
        </HelpSection>

        <HelpSection title="Admin Sign-In Options">
          <p>
            <span className="font-medium text-gray-800">Local admin</span> — a username/password
            account created on first run from the bootstrap environment values (see below), only
            when the user table is empty.
          </p>
          <p>
            <span className="font-medium text-gray-800">Sign in with Omnissa Access</span> — OIDC
            single sign-on, configured with these container environment values:
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <EnvVar name="OMNISSA_ADMIN_OAUTH_CLIENT_ID" /> — the OAuth2 client ID created in
              Omnissa Access (e.g. <Code>ApprovalAdmin</Code>).
            </li>
            <li>
              <EnvVar name="OMNISSA_ADMIN_OAUTH_CLIENT_SECRET" /> — that client's shared secret.
            </li>
            <li>
              <EnvVar name="OMNISSA_ADMIN_OAUTH_REDIRECT_URI" /> — must exactly match the redirect
              URI registered on the Access client; format{' '}
              <Code>https://&lt;your-host&gt;/login/oauth2/code/omnissa</Code>.
            </li>
            <li>
              <EnvVar name="OMNISSA_ADMIN_OAUTH_ISSUER_URI" /> — the tenant's OIDC issuer:{' '}
              <Code>https://&lt;tenant&gt;/SAAS/auth</Code> (the issuer value from{' '}
              <Code>/.well-known/openid-configuration</Code> — NOT <Code>/acs</Code>).
            </li>
            <li>
              <EnvVar name="OMNISSA_ADMIN_OAUTH_DISABLE_CONSENT" /> — <Code>true</Code> = the app
              disables the client's user-consent prompt via the Access admin API at startup
              (requires the service client to have admin rights).
            </li>
          </ul>
          <p>
            Required Omnissa Access client settings: type{' '}
            <span className="font-medium text-gray-800">User Access Token</span> (confidential),
            grant <Code>authorization_code</Code>, PKCE enforced is supported, scopes{' '}
            <Code>openid email profile</Code>.
          </p>
          <p>Bootstrap environment values:</p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <EnvVar name="OMNISSA_BOOTSTRAP_URL" /> / <EnvVar name="OMNISSA_BOOTSTRAP_CLIENT_ID" /> /{' '}
              <EnvVar name="OMNISSA_BOOTSTRAP_CLIENT_SECRET" /> — the service client used for the
              approvals API and the connectivity check.
            </li>
            <li>
              <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_USERNAME" /> / <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_PASSWORD" /> /{' '}
              <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_EMAIL" /> — the first-run local admin, created
              only when the user table is empty.
            </li>
          </ul>
          <p>
            Setting <EnvVar name="OMNISSA_AUTH_LOCAL_LOGIN_DISABLED" />=<Code>true</Code> forces
            OAuth-only sign-in: the local username/password form is hidden and only "Sign in with
            Omnissa Access" is available.
          </p>
        </HelpSection>

        <HelpSection title="Roles and Permissions">
          <p>
            What a signed-in user may do is decided by their{' '}
            <span className="font-medium text-gray-800">Omnissa Access group membership</span>.
          </p>
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm border border-gray-200 rounded-lg">
              <thead className="bg-gray-50">
                <tr>
                  <th className="text-left font-medium text-gray-800 px-3 py-2 border-b border-gray-200">Role</th>
                  <th className="text-left font-medium text-gray-800 px-3 py-2 border-b border-gray-200">Can</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td className="px-3 py-2 border-b border-gray-100 font-medium text-gray-800">Admin</td>
                  <td className="px-3 py-2 border-b border-gray-100">
                    Manage users, auto-approval rules, tenant configuration and the log bundle;
                    delete requests — plus everything below
                  </td>
                </tr>
                <tr>
                  <td className="px-3 py-2 border-b border-gray-100 font-medium text-gray-800">Approver</td>
                  <td className="px-3 py-2 border-b border-gray-100">
                    Decide requests — approve, reject, revoke, revoke and block, allow re-request.
                    Can <span className="font-medium">read</span> rules but not change them
                  </td>
                </tr>
                <tr>
                  <td className="px-3 py-2 border-b border-gray-100 font-medium text-gray-800">Viewer</td>
                  <td className="px-3 py-2 border-b border-gray-100">
                    Read the queue, request details, statistics, rules and the audit trail on
                    screen. Changes nothing, and cannot export
                  </td>
                </tr>
                <tr>
                  <td className="px-3 py-2 font-medium text-gray-800">Auditor</td>
                  <td className="px-3 py-2">
                    The audit trail <span className="font-medium">only</span>, including its CSV
                    export — no live queue, no request details, no decisions
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p>
            Map groups to roles in the env file with <EnvVar name="OMNISSA_ROLE_MAP" />, as
            comma-separated <Code>{'<groupId>:<ROLE>'}</Code> pairs:
          </p>
          <CodeBlock>{'OMNISSA_ROLE_MAP=05eb7969-…:ADMIN,63173f00-…:APPROVER,4378e8f5-…:AUDITOR'}</CodeBlock>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">It matches group ids, not names.</span> A
              rename in Access would otherwise silently drop everyone to Viewer with no error. Sign
              in and open <Code>/api/auth/claims</Code> to read the ids — it pairs each id with its
              display name.
            </li>
            <li>
              <span className="font-medium text-gray-800">Viewer is a fallback, not a floor.</span>{' '}
              A user whose groups match nothing gets Viewer; once any group matches, the matched
              roles are exactly what they hold. With no map configured, everyone is a Viewer — so
              setting the map is the deliberate act that grants privilege.
            </li>
            <li>
              <span className="font-medium text-gray-800">Multiple matches are additive, and the
              most permissive wins.</span> Someone in the approver and auditor groups holds both
              roles and gets the union of their permissions.
            </li>
            <li>
              <span className="font-medium text-gray-800">Changes take effect at next sign-in.</span>{' '}
              Roles are read from the token, which is a snapshot — adding or removing a group does
              nothing until the user signs out and back in.
            </li>
          </ul>
          <p className="text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            <span className="font-medium">Note —</span> Auditor is the only{' '}
            <span className="font-medium">restrictive</span> role, so combining it with any other
            role silently defeats it: permissions are additive, so the user keeps full access to the
            live queue and Auditor contributes nothing. Adding an approver to the auditors group
            looks like a restriction and is not one. Paired with Admin or Approver it is also a{' '}
            <span className="font-medium">separation-of-duties conflict</span> — the same person
            decides requests and audits those decisions. The combination is permitted but a warning
            is written to the log at each sign-in; grant Auditor{' '}
            <span className="font-medium">on its own</span> when the intent is to restrict.
          </p>
          <p>
            <span className="font-medium text-gray-800">Exporting is gated separately from
            reading.</span> A bulk export is an extraction rather than a read — it produces a file
            that leaves the tool's controls entirely and can be retained or shared without trace —
            so <span className="font-medium text-gray-800">Viewers can read the audit trail on
            screen but cannot download it</span>. The audit-trail export is available to Admins and
            Auditors; the request export to Admins, Approvers and Auditors.
          </p>
          <p>
            Sign-in method and permissions are separate concerns:{' '}
            <span className="font-medium text-gray-800">Teams</span> approvals respect roles, since
            the card's buttons open the tool and the approver signs in normally.{' '}
            <span className="font-medium text-gray-800">Slack</span> approvals do not — a Slack
            decision is made in the interaction callback where there is no signed-in user to check,
            so authority there comes solely from <EnvVar name="SLACK_APPROVER_MAP" />. Keep that map
            in step with group membership. Note also that anyone in a notification channel can{' '}
            <span className="font-medium">read</span> request details regardless of role; roles
            govern who may act, never who may see.
          </p>
        </HelpSection>

        <HelpSection title="Security Options">
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <EnvVar name="OMNISSA_API_USERNAME" /> / <EnvVar name="OMNISSA_API_PASSWORD" /> — enable
              HTTP Basic authentication on the approval callout API. Set the same values in the
              Omnissa Access approvals settings Username and Password fields.
            </li>
            <li>
              <EnvVar name="OMNISSA_AUTH_LOCAL_LOGIN_DISABLED" />=<code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">true</code> —
              disables local username/password login.
            </li>
          </ul>
        </HelpSection>

        <HelpSection title="Audit Trail">
          <p>
            Every incoming request, every decision (with the deciding admin's username), and every
            auto-rule action is recorded in the audit trail. View it under{' '}
            <span className="font-medium text-gray-800">Queue &gt; Audit</span> tab. Audit lines are
            also written to the application log with the{' '}
            <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">AUDIT</code>{' '}
            prefix, so they appear in the downloadable log bundle and syslog export.
          </p>
          <p>
            Decision lines in the log and syslog export carry named attribution in the message,
            e.g. <Code>Approved by &lt;admin&gt;</Code>, <Code>Rejected by &lt;admin&gt; (bulk
            action)</Code>, or <Code>Auto-Approved by rule #N</Code>.
          </p>
        </HelpSection>

        <HelpSection title="Access Duration, Revoking and Blocking">
          <p>
            Approving is not only yes/no — you also choose <span className="font-medium text-gray-800">how
            long</span> the access lasts and <span className="font-medium text-gray-800">whether it can
            come back</span>.
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Access duration</span> — Permanent, or a
              time-bound (JIT) grant from 5 minutes to 30 days. A timed grant is revoked
              automatically when it expires, which removes the app from the user in Omnissa Access.
            </li>
            <li>
              <span className="font-medium text-gray-800">After expiry</span> — leave{' '}
              <Code>Allow the user to re-request</Code> ticked and the app returns to a requestable
              state about a minute after expiry; untick it for one-time access that does not
              reappear.
            </li>
            <li>
              <span className="font-medium text-gray-800">Rejecting</span> — a{' '}
              <Code>Temporary</Code> decline rejects only that request; a <Code>Permanent</Code>{' '}
              decline also excludes the user so the app will not reappear for them.
            </li>
            <li>
              <span className="font-medium text-gray-800">Revoking granted access</span> — open an
              approved request and choose <Code>Revoke access</Code> (the app returns to a
              requestable state shortly) or <Code>Revoke and block</Code> (the user stays excluded).
              No need to wait for a TTL.
            </li>
            <li>
              <span className="font-medium text-gray-800">Allow re-request</span> — any blocked
              request shows this action on its detail page. It lifts the exclusion so the user can
              request the app again, and is fully audited. Blocked records live under the{' '}
              <span className="font-medium text-gray-800">Deactivated</span> tab.
            </li>
          </ul>
          <p>
            This works whether the app is assigned to the user directly or through a group: the tool
            toggles a per-user <span className="font-medium text-gray-800">exclusion</span>, leaving
            the group assignment untouched. If a revoke or block cannot be applied in Omnissa Access,
            it is <span className="font-medium text-gray-800">not</span> recorded as if it had
            succeeded.
          </p>
          <p className="text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            <span className="font-medium">Note —</span> after a revoke that allows re-requesting,
            access can be handed straight back: if the group assignment's{' '}
            <span className="font-medium">Deployment Type</span> is{' '}
            <span className="font-medium">Automatic</span>, Omnissa Access re-provisions the app as
            soon as the exclusion lifts, and a matching auto-approval rule will approve a new request
            immediately. Use <Code>Revoke and block</Code> when access must stay gone.
          </p>
        </HelpSection>

        <HelpSection title="Approving from Slack or Teams">
          <p>
            Requests can be decided from chat instead of the queue. Both options are configured in
            the tool's <span className="font-medium text-gray-800">env file</span> (
            <Code>omnissa-approvals.env</Code>, or <Code>.env</Code> for the bundled Compose files),
            on top of the webhook settings described in the next section.
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Slack</span> — in the env file set{' '}
              <EnvVar name="SLACK_ACTIONABLE" />=<Code>true</Code> with{' '}
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>slack</Code> to post an interactive message with
              an access-duration menu and Approve / Reject / Reject-and-Block buttons; the message
              updates in place with the outcome. Requires{' '}
              <EnvVar name="SLACK_SIGNING_SECRET" /> (every click is signature-verified) and{' '}
              <EnvVar name="SLACK_APPROVER_MAP" /> — only listed Slack users may decide, so being in
              the channel is not enough.
            </li>
            <li>
              <span className="font-medium text-gray-800">Microsoft Teams</span> — in the env file set{' '}
              <EnvVar name="TEAMS_ACTIONABLE" />=<Code>true</Code> with{' '}
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>teams</Code> and{' '}
              <EnvVar name="APP_BASE_URL" />. Posts an Adaptive Card whose buttons open the request
              here with the decision pre-selected — you sign in as usual, so the decision is
              attributed to your account. Post it through a Power Automate{' '}
              <span className="font-medium text-gray-800">Send webhook alerts to a channel</span>{' '}
              workflow (Office 365 connectors are retired).
            </li>
          </ul>

          <h3 className="font-semibold text-gray-800 mt-5 mb-2">Slack — setup in 6 steps</h3>
          <ol className="list-decimal pl-5 space-y-1">
            <li>
              At <Code>api.slack.com/apps</Code> click{' '}
              <span className="font-medium text-gray-800">Create an App → From scratch</span>, name
              it, and pick your workspace.
            </li>
            <li>
              <span className="font-medium text-gray-800">Incoming Webhooks</span> → toggle{' '}
              <Code>On</Code> → <span className="font-medium text-gray-800">Add New Webhook to
              Workspace</span> → choose the approvals channel → <Code>Allow</Code>. Copy the{' '}
              <Code>https://hooks.slack.com/services/…</Code> URL.
            </li>
            <li>
              <span className="font-medium text-gray-800">Interactivity &amp; Shortcuts</span> →
              toggle <Code>On</Code> → set the{' '}
              <span className="font-medium text-gray-800">Request URL</span> to{' '}
              <Code>https://&lt;your-host&gt;/api/slack/interactions</Code> → <Code>Save</Code>.
            </li>
            <li>
              <span className="font-medium text-gray-800">Basic Information → App Credentials</span>{' '}
              → reveal and copy the{' '}
              <span className="font-medium text-gray-800">Signing Secret</span>. For each approver,
              open their Slack profile → <Code>⋮</Code> →{' '}
              <span className="font-medium text-gray-800">Copy member ID</span> (a <Code>U…</Code>{' '}
              value). No bot token or app-level token is needed.
            </li>
            <li>
              In the env file set the five values, then recreate the container (a restart does not
              re-read the env file):
              <CodeBlock>{'WEBHOOK_URL=https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXX\nWEBHOOK_FORMAT=slack\nSLACK_ACTIONABLE=true\nSLACK_SIGNING_SECRET=<signing secret>\nSLACK_APPROVER_MAP=U0123ABC:dean@example.com,U0456DEF:jane'}</CodeBlock>
            </li>
            <li>
              Make <Code>POST /api/slack/interactions</Code> reachable from the internet through your
              reverse proxy — same treatment as <Code>/api/approvals/new</Code>. Behind a UAG, add
              the path to the <span className="font-medium text-gray-800">proxyPattern</span> and
              keep <span className="font-medium text-gray-800">Identity Bridging off</span> (Slack's
              callback carries no user identity). Then request an app and confirm the card appears.
            </li>
          </ol>
          <p className="text-gray-600">
            <span className="font-medium text-gray-800">Authorization is separate from the
            signature.</span> A valid signature only proves the request came from your workspace —
            only Slack ids listed in <EnvVar name="SLACK_APPROVER_MAP" /> may decide, and everyone
            else in the channel is rejected and audited.
          </p>

          <h3 className="font-semibold text-gray-800 mt-5 mb-2">Teams — setup in 4 steps</h3>
          <ol className="list-decimal pl-5 space-y-1">
            <li>
              In Teams open the <span className="font-medium text-gray-800">Workflows</span> app →{' '}
              <span className="font-medium text-gray-800">Templates</span> → search{' '}
              <Code>webhook</Code> → choose{' '}
              <span className="font-medium text-gray-800">"Send Webhook Alerts to a Channel"</span>.
            </li>
            <li>
              Pick the team and channel, finish the template, then click{' '}
              <span className="font-medium text-gray-800">Copy webhook link</span>. The URL is on{' '}
              <Code>*.environment.api.powerplatform.com</Code> — a{' '}
              <Code>webhook.office.com</Code> URL is the retired connector and will not work.
            </li>
            <li>
              In the env file set the four values, then recreate the container:
              <CodeBlock>{'WEBHOOK_URL=<the workflow URL>\nWEBHOOK_FORMAT=teams\nTEAMS_ACTIONABLE=true\nAPP_BASE_URL=https://approvals.example.com'}</CodeBlock>
            </li>
            <li>
              Request an app and confirm the card posts. No inbound endpoint is required — the card's
              buttons are deep links, so only the <span className="font-medium text-gray-800">approver's
              browser</span> reaches the tool. If the UI is LAN-only, approvers need the LAN or VPN.
            </li>
          </ol>
          <p className="text-gray-600">
            <EnvVar name="APP_BASE_URL" /> is mandatory here: notifications are sent from a
            background thread with no HTTP request, so the public URL cannot be derived from
            forwarded headers. If it is blank the tool sends plain text rather than broken links. If
            the card posts but does not render, edit the flow's{' '}
            <span className="font-medium text-gray-800">Post card in a chat or channel</span> action
            so its message body is the raw trigger body rather than a text field.
          </p>
        </HelpSection>

        <HelpSection title="Webhook Notifications">
          <p>
            All values below are set in the tool's <span className="font-medium text-gray-800">env
            file</span> — <Code>omnissa-approvals.env</Code> for the ZimaCube/Docker deployment,{' '}
            <Code>.env</Code> for the bundled Compose files — or as container environment values if
            your platform manages them that way. Changes need a container recreate, not a restart.
          </p>
          <p>
            Set <EnvVar name="WEBHOOK_URL" /> in the env file to POST a notification for each new
            access request and for each decision (approved or rejected, whether made by an admin or
            an auto-approval rule), and <EnvVar name="WEBHOOK_FORMAT" /> to match the receiving
            system:
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Slack</span> — create an Incoming Webhook:
              on <Code>api.slack.com/apps</Code> open your app, go to{' '}
              <span className="font-medium text-gray-800">Incoming Webhooks</span>, activate them,
              click <span className="font-medium text-gray-800">Add New Webhook to Workspace</span>,
              and pick a channel. You get a URL like{' '}
              <Code>https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX</Code>.
              Set <EnvVar name="WEBHOOK_FORMAT" />=<Code>slack</Code>.
            </li>
            <li>
              <span className="font-medium text-gray-800">Microsoft Teams</span> — in the channel,
              click <span className="font-medium text-gray-800">⋯ &gt; Workflows</span> and choose{' '}
              <span className="font-medium text-gray-800">"Send Webhook Alerts to a Channel"</span>{' '}
              (the successor of the Incoming Webhook connector, which Microsoft has retired), pick
              the team and channel, then click{' '}
              <span className="font-medium text-gray-800">Copy webhook link</span> to copy the URL —
              a Power Automate address on{' '}
              <Code>*.environment.api.powerplatform.com</Code>. Set{' '}
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>teams</Code>.
            </li>
            <li>
              <span className="font-medium text-gray-800">Generic</span> — any endpoint that accepts
              a JSON POST (n8n, a Zapier catch hook, a custom script). Set{' '}
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>generic</Code>.
            </li>
            <li>
              <span className="font-medium text-gray-800">Quick test</span> — open{' '}
              <Code>webhook.site</Code>, copy your unique URL, set{' '}
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>generic</Code>, request an app, and watch the
              payload arrive live.
            </li>
          </ul>
          <p>Example configuration:</p>
          <CodeBlock>{'WEBHOOK_URL=https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX\nWEBHOOK_FORMAT=slack'}</CodeBlock>
          <p>
            Example <Code>generic</Code> payload for a new request (<Code>request.created</Code>):
          </p>
          <CodeBlock>{'{"event":"request.created","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","operation":"activation","receivedDate":"2026-07-03T08:11:43Z"}'}</CodeBlock>
          <p>
            Example <Code>generic</Code> payloads for a decision (<Code>request.decided</Code>) —
            an admin decision, then an auto-rule decision (<Code>decidedBy</Code> is{' '}
            <Code>auto-approval-rule</Code> and <Code>rule</Code> carries the rule number):
          </p>
          <CodeBlock>{'{"event":"request.decided","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","decision":"approved","decidedBy":"dean","decidedDate":"2026-07-03T18:00:00Z"}'}</CodeBlock>
          <CodeBlock>{'{"event":"request.decided","requestId":"8ab7df4b-...","resourceName":"Example App (SAML)","userId":"123456","decision":"rejected","decidedBy":"auto-approval-rule","rule":"#7","decidedDate":"2026-07-03T18:00:00Z"}'}</CodeBlock>
          <p>
            Example <Code>slack</Code>/<Code>teams</Code> payloads — new request, admin decision,
            auto-rule decision:
          </p>
          <CodeBlock>{'{"text":"New access request: Example App (SAML) requested by user 123456 — approve or reject in the Access Approval Tool."}'}</CodeBlock>
          <CodeBlock>{'{"text":"Approved by dean: Example App (SAML) (user 123456)"}'}</CodeBlock>
          <CodeBlock>{'{"text":"Auto-Rejected by rule #5: Example App (SAML) (user 123456)"}'}</CodeBlock>
          <p>
            Configuration changes are made in the env file (or container key values) and require a
            container recreate to apply. Delivery failures never block request ingestion or
            decisions — they are logged as a WARN.
          </p>
        </HelpSection>

        <HelpSection title="Auto-Approval Rules">
          <p>
            Rules are managed on the <span className="font-medium text-gray-800">Rules</span> page.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <span className="font-medium text-gray-800">Match rules</span> auto-approve or
              auto-reject requests on arrival, by application name pattern (<Code>*</Code> wildcard)
              and/or Access group membership.
            </li>
            <li>
              <span className="font-medium text-gray-800">Expiry rules</span> auto-reject requests
              that stay pending longer than N days (checked hourly).
            </li>
          </ul>
          <p className="font-medium text-gray-800">Wildcard matching</p>
          <p>
            Matching is case-insensitive against the full application name. <Code>*</Code> matches
            any sequence of characters; everything else is literal. Multiple <Code>*</Code> are
            allowed anywhere in the pattern. Group name matching is an exact (case-insensitive)
            match against the requesting user's Access group list — the group is NOT a pattern.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <Code>*</Code> matches every application.
            </li>
            <li>
              <Code>Example App*</Code> matches "Example App (SAML)" and "Example App
              (OIDC)".
            </li>
            <li>
              <Code>*SAML*</Code> matches any app containing SAML.
            </li>
            <li>
              <Code>Salesforce</Code> matches only the app named exactly Salesforce.
            </li>
            <li>
              Combined example: action <Code>approve</Code> + app pattern <Code>*Office*</Code> +
              group name <Code>MS Office Apps</Code> auto-approves Office apps only for members of
              that group.
            </li>
            <li>Expiry example: reject after 7 days pending.</li>
          </ul>
          <p className="font-medium text-gray-800">Precedence</p>
          <p>
            Rules are evaluated in ascending rule number (creation) order; the FIRST enabled rule
            that matches wins and later rules are ignored. Example conflict: rule{' '}
            <span className="font-medium text-gray-800">#1</span> approve <Code>*Office*</Code> and
            rule <span className="font-medium text-gray-800">#2</span> reject <Code>*</Code> — a
            request for "MS Office" is auto-approved because rule #1 matches first, while every
            other app is auto-rejected by rule #2. Disabled rules are skipped.
          </p>
          <p>
            Expiry rules run independently on an hourly scheduler and only affect requests still
            pending, so they never conflict with match rules that already decided a request.
          </p>
          <p>
            All auto-decisions appear in the <span className="font-medium text-gray-800">Audit</span>{' '}
            tab as <span className="font-medium text-gray-800">auto-approved</span> or{' '}
            <span className="font-medium text-gray-800">auto-rejected</span>, with the rule number.
          </p>
        </HelpSection>

        <HelpSection title="Access Connectivity">
          <p>
            The dashboard tile checks that the service client can obtain a token from the Omnissa
            Access tenant (result cached for about 60 seconds). A red status usually means an
            expired or changed client secret.
          </p>
        </HelpSection>

        <HelpSection title="CSV Export">
          <p>
            On the <span className="font-medium text-gray-800">Queue &gt; Audit</span> tab, click{' '}
            <span className="font-medium text-gray-800">Export CSV</span> to download the full
            request history, including who decided each request.
          </p>
        </HelpSection>

        <HelpSection title="Rate Limiting">
          <p>
            The callout endpoint is limited to <EnvVar name="OMNISSA_API_RATE_LIMIT" /> requests per
            minute per source IP (default 60; set to{' '}
            <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">0</code> to
            disable). Excess requests receive HTTP 429.
          </p>
        </HelpSection>

        <HelpSection title="Logs">
          {/* The log bundle endpoint is administrator-only. */}
          {canAdminister(user) && (
            <a
              href="/api/logs/bundle"
              download
              className="inline-block bg-omnissa text-white rounded-lg px-4 py-2 text-sm font-medium hover:bg-omnissa-dark transition-colors"
            >
              Download Log Bundle (last hour)
            </a>
          )}
          <p>
            Application logs can also be forwarded to a syslog server. Set the{' '}
            <EnvVar name="SYSLOG_HOST" /> container environment value, then restart the container
            to apply. <EnvVar name="SYSLOG_PORT" /> takes a port number only (e.g.{' '}
            <Code>SYSLOG_PORT=514</Code>); the default is 514. The transport is chosen by{' '}
            <EnvVar name="SYSLOG_PROTOCOL" />: <Code>udp</Code> (default), <Code>tcp</Code>, or{' '}
            <Code>tls</Code>.
          </p>
          <p>
            For <Code>tls</Code>, client-certificate authentication is supported: paste PEM into{' '}
            <EnvVar name="SYSLOG_CLIENT_CERT_PEM" /> / <EnvVar name="SYSLOG_CLIENT_KEY_PEM" />{' '}
            (optional <EnvVar name="SYSLOG_CA_PEM" /> for a private CA), or preferably point{' '}
            <EnvVar name="SYSLOG_CLIENT_CERT_FILE" /> / <EnvVar name="SYSLOG_CLIENT_KEY_FILE" /> /{' '}
            <EnvVar name="SYSLOG_CA_FILE" /> at files under <Code>/app/data</Code> (e.g.{' '}
            <Code>/app/data/certs/client.pem</Code>) — easier than pasting multiline PEM into a UI.
            The private key must be PKCS#8 (<Code>BEGIN PRIVATE KEY</Code>; convert with{' '}
            <Code>openssl pkcs8 -topk8 -nocrypt</Code> if needed).
          </p>
          <p>Example:</p>
          <CodeBlock>{'SYSLOG_HOST=syslog.example.com\nSYSLOG_PORT=6514\nSYSLOG_PROTOCOL=tls\nSYSLOG_CLIENT_CERT_FILE=/app/data/certs/client.pem\nSYSLOG_CLIENT_KEY_FILE=/app/data/certs/client-key.pem'}</CodeBlock>
        </HelpSection>

        <HelpSection title="Updates">
          <p>Three ways to update the container to a newly published image:</p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Re-run the deploy script</span> (ZimaCube)
              — <Code>sudo sh deploy/zimacube/deploy.sh</Code> pulls the latest image and recreates
              the container. Equivalent for any Docker host:
              <CodeBlock>{'docker compose -f <compose file> pull\ndocker compose -f <compose file> up -d'}</CodeBlock>
            </li>
            <li>
              <span className="font-medium text-gray-800">Optional Watchtower auto-update</span> —
              the ZimaCube compose file includes a Watchtower service behind the{' '}
              <Code>autoupdate</Code> compose profile. It is{' '}
              <span className="font-medium text-gray-800">disabled by default</span>; when enabled it
              checks the registry daily and recreates only this container (label-scoped — it never
              touches other containers on the host). Enable with:
              <CodeBlock>{'docker compose -f <compose file> --profile autoupdate up -d'}</CodeBlock>
              Disable by stopping/removing the watchtower container:
              <CodeBlock>{'docker compose -f <compose file> --profile autoupdate down watchtower'}</CodeBlock>
              Note: Watchtower requires the Docker socket, which grants it control of the Docker
              engine — the reason it ships disabled. See the deployment guide for details.
            </li>
            <li>
              <span className="font-medium text-gray-800">CasaOS warning</span> — the CasaOS{' '}
              <span className="font-medium text-gray-800">"Check and then update"</span> button does
              NOT work for this container. It always reports{' '}
              <span className="italic">"is the latest version"</span>, even when a newer image is
              published. ZimaOS decides whether an update exists by looking the app up in a{' '}
              <span className="font-medium text-gray-800">CasaOS AppStore</span>; an
              externally-managed Compose app is never found there, so the check gives up before it
              ever contacts the registry. No image tag changes this. Use one of the two methods above
              instead, and treat the version shown on the dashboard as the authoritative answer to
              what you are running.
            </li>
          </ul>
          <p>
            All state (H2 database, certificates) lives on the mounted data volume, so a container
            recreate during an update loses nothing.
          </p>
        </HelpSection>

        <HelpSection title="Configuration Reference">
          <p>
            All container environment values, their defaults, and what they do. Values are set in
            the env file (or container key values) and require a container recreate to apply.
          </p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead>
                <tr className="border-b border-gray-100 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
                  <th className="px-3 py-2">Variable</th>
                  <th className="px-3 py-2">Default</th>
                  <th className="px-3 py-2">Purpose</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {CONFIG_VARS.map(v => (
                  <tr key={v.name}>
                    <td className="px-3 py-2 whitespace-nowrap"><EnvVar name={v.name} /></td>
                    <td className="px-3 py-2 whitespace-nowrap text-gray-500">{v.def}</td>
                    <td className="px-3 py-2 text-gray-600">{v.purpose}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </HelpSection>
      </div>
    </div>
  )
}
