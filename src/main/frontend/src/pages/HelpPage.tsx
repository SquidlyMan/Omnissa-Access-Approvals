import { useEffect, useState } from 'react'
import { useAuth } from '../hooks/useAuth'
import { canAdminister } from '../lib/permissions'

interface HelpSectionProps {
  title: string
  children: React.ReactNode
}

/** Stable anchor from a section title: "Roles and Permissions" -> "roles-and-permissions". */
function slugify(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

function HelpSection({ title, children }: HelpSectionProps) {
  return (
    <section
      id={slugify(title)}
      data-help-section={title}
      className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6 scroll-mt-6"
    >
      <h2 className="font-semibold text-gray-800 text-lg mb-3">{title}</h2>
      <div className="text-sm text-gray-600 space-y-3">{children}</div>
    </section>
  )
}

/**
 * Contents, read from the rendered sections rather than a hand-kept list.
 *
 * The page gains sections regularly — three were added in one day — and a
 * duplicated list would quietly fall out of step with them. Reading the DOM
 * means a new HelpSection appears here automatically and can never be missed.
 */
function HelpContents() {
  const [entries, setEntries] = useState<{ id: string; title: string }[]>([])

  useEffect(() => {
    const sections = Array.from(document.querySelectorAll<HTMLElement>('[data-help-section]'))
    setEntries(sections.map(el => ({ id: el.id, title: el.dataset.helpSection ?? '' })))
  }, [])

  if (entries.length === 0) return null

  return (
    <nav aria-label="Help contents" className="bg-white rounded-xl border border-gray-200 p-4 sm:p-6 mb-6">
      <h2 className="font-semibold text-gray-800 text-lg mb-3">Contents</h2>
      <ol className="grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-3 text-sm">
        {entries.map((entry, i) => (
          <li key={entry.id} className="flex gap-2">
            <span className="text-gray-400 tabular-nums w-5 shrink-0 text-right">{i + 1}.</span>
            <a href={`#${entry.id}`} className="text-omnissa hover:underline">
              {entry.title}
            </a>
          </li>
        ))}
      </ol>
    </nav>
  )
}

/** Appears once the contents list has scrolled out of reach. */
function BackToTop() {
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const onScroll = () => setVisible(window.scrollY > 400)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  if (!visible) return null

  return (
    <button
      type="button"
      onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
      aria-label="Back to contents"
      title="Back to contents"
      className="fixed bottom-6 right-6 z-40 rounded-full bg-omnissa text-white shadow-lg
                 w-11 h-11 flex items-center justify-center hover:opacity-90 transition-opacity"
    >
      <svg viewBox="0 0 20 20" fill="currentColor" className="w-5 h-5" aria-hidden="true">
        <path fillRule="evenodd" clipRule="evenodd"
              d="M10 17a1 1 0 0 1-1-1V6.41L5.7 9.7a1 1 0 0 1-1.4-1.42l5-5a1 1 0 0 1 1.4 0l5 5a1 1 0 1 1-1.4 1.42L11 6.41V16a1 1 0 0 1-1 1Z" />
      </svg>
    </button>
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
  { name: 'SLACK_ACTIONABLE', def: 'false', purpose: 'true adds Approve / Reject / Open buttons to the Slack message. They are deep links that open the request here with the decision pre-selected, so the approver signs in and their role applies. Requires WEBHOOK_FORMAT=slack and APP_BASE_URL.' },
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

      <div className="max-w-4xl">
        <HelpContents />
      </div>

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
              <span className="font-medium text-gray-800">Password</span> fields must match{' '}
              <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">OMNISSA_API_USERNAME</code>{' '}
              and{' '}
              <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">OMNISSA_API_PASSWORD</code>{' '}
              on this tool. They are required: once a tenant is configured the tool refuses to start
              without them, because this endpoint faces the internet. Access does not verify them when
              you save, so a mismatch shows up later as callouts rejected with 401 and new requests
              never reaching the queue — check the application log, which names what was presented.
            </li>
            <li>
              <span className="font-medium text-gray-800">Press Save here after setting the
              credentials on the tool — even if nothing on this screen changed.</span> Access decides
              whether an endpoint requires authentication by probing it, and only re-decides when
              these settings are saved. Set the credentials on the tool without saving here, and
              Access carries on posting unauthenticated indefinitely — every callout rejected, no
              requests arriving — whatever both sides are configured with.
            </li>
          </ul>
          <p className="mt-3 text-sm text-gray-600">
            Two further behaviours worth knowing when reading the log. Access never sends credentials
            up front: every callout begins unauthenticated, takes the 401, and is retried with them,
            so a single unauthenticated attempt is the normal first half of the exchange rather than a
            fault. And Access delivers each callout from more than one node, so the same request
            arriving twice is expected — the second copy is acknowledged and discarded rather than
            queued a second time.
          </p>
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
                    Manage users, auto-approval rules, approval chains, tenant configuration and the
                    log bundle; delete requests — plus everything below. Can always decide any stage
                    of any approval chain, regardless of that stage's requirement
                  </td>
                </tr>
                <tr>
                  <td className="px-3 py-2 border-b border-gray-100 font-medium text-gray-800">Approver</td>
                  <td className="px-3 py-2 border-b border-gray-100">
                    Decide requests — approve, reject, revoke, revoke and block, allow re-request.
                    Can <span className="font-medium">read</span> rules and chains but not change
                    them. For a request on an approval chain, only eligible for the chain's{' '}
                    <span className="font-medium">current stage</span> — see Approval Chains below
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
            <span className="font-medium text-gray-800">Slack</span> approvals respect roles for
            the same reason — both use deep links rather than callbacks, so the approver signs in
            and the ordinary rules apply. Note that anyone in a notification channel can{' '}
            <span className="font-medium">read</span> request details regardless of role; roles
            govern who may act, never who may see.
          </p>
        </HelpSection>

        <HelpSection title="Local Accounts">
          <p>
            Local username/password sign-in exists as the{' '}
            <span className="font-medium text-gray-800">break-glass</span> route. Roles come from
            Omnissa Access group membership, so if the tenant is unreachable, the OIDC client is
            misconfigured, or <EnvVar name="OMNISSA_ROLE_MAP" /> is wrong, a local admin is the only
            way back in.
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Change my password</span> — in the top
              bar, for any account signed in <span className="font-medium">locally</span>. Requires
              your current password. Signed in with Omnissa Access? That credential lives in your
              identity provider, so the option does not appear.
            </li>
            <li>
              <span className="font-medium text-gray-800">Users</span> page (admins only) — add a
              local account, reset a password, enable or disable an account, change its roles, or
              delete it. Every one of these is recorded in the audit trail.
            </li>
          </ul>
          <p className="text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            <span className="font-medium">Note —</span> the tool refuses to disable, delete or
            demote the <span className="font-medium">last enabled local administrator</span>, and
            explains why rather than just failing. An Omnissa Access user holding the Admin role
            through a group does not satisfy this: the situations break-glass exists for are exactly
            the ones where Access sign-in does not work. Grant a second local account the Admin role
            first.
          </p>
          <p>
            <span className="font-medium text-gray-800">The bootstrap variables cannot rotate a
            password.</span> <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_PASSWORD" /> is read only when
            the user table is empty, so changing it on an existing install does nothing — silently.
            Use <span className="font-medium text-gray-800">Reset password</span> on the Users page
            instead.
          </p>
          <p>
            <span className="font-medium text-gray-800">Repeated failed sign-ins are slowed
            progressively</span>, and an address making sustained attempts is refused for a while.
            There is deliberately <span className="font-medium text-gray-800">no account
            lockout</span> — locking an account would let anyone who can reach the login page
            disable the break-glass credential exactly when it is needed. Counters clear
            themselves, and a successful sign-in resets them.
          </p>
          <p>Password rules (defaults — see <Code>docs/configuration.md</Code> to adjust):</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>At least <span className="font-medium text-gray-800">12 characters</span></li>
            <li>Not made of a handful of repeated characters</li>
            <li>Not a well-known password, and not a plain sequence</li>
            <li>Must not contain the username</li>
            <li>
              <span className="font-medium text-gray-800">No uppercase / digit / symbol
              requirement</span> by default — a passphrase such as{' '}
              <Code>correct horse battery staple</Code> is accepted and is stronger than a short
              complex one. Composition rules mostly turn <Code>password</Code> into{' '}
              <Code>Password1!</Code>, which is exactly what a cracking tool generates first. They
              can be switched on for a compliance requirement.
            </li>
          </ul>
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
          <div className="text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 space-y-2">
            <p>
              <span className="font-medium">Note —</span> when the exclusion lifts (a{' '}
              <Code>Revoke access</Code>, an <Code>Allow re-request</Code>, or a re-requestable JIT
              expiry), whether the user gets the app back immediately depends on the assignment's{' '}
              <span className="font-medium">Deployment Type</span> in Omnissa Access:
            </p>
            <ul className="list-disc pl-5 space-y-1">
              <li>
                <span className="font-medium">Automatic</span> — Omnissa Access re-provisions the app
                straight away. The user never requests it, so no approval happens at all; an
                auto-approval rule is not involved, because there is no request to approve.
              </li>
              <li>
                <span className="font-medium">User-Activated</span> — the app reappears in the
                catalog as <span className="font-medium">requestable</span>. Nothing is granted yet.
                When the user clicks <span className="font-medium">Request</span>, a new request
                reaches this tool, and <span className="font-medium">then</span> a matching
                auto-approval rule approves it immediately. With no matching rule it waits in the
                queue like any other request.
              </li>
            </ul>
            <p>
              So an auto-approval rule never hands access back on its own — it acts on the{' '}
              <span className="font-medium">next request</span>, which only exists under
              User-Activated. Use <Code>Revoke and block</Code> when access must stay gone
              regardless.
            </p>
          </div>
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
              <EnvVar name="WEBHOOK_FORMAT" />=<Code>slack</Code> and{' '}
              <EnvVar name="APP_BASE_URL" />. Adds Approve / Reject / Open buttons that open the
              request here with the decision pre-selected — you sign in as usual, so the decision is
              attributed to your account and your role decides what you may do.
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

          <h3 className="font-semibold text-gray-800 mt-5 mb-2">Slack — setup in 4 steps</h3>
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
              <Code>https://hooks.slack.com/services/…</Code> URL. That is the only value you need —
              no signing secret, no bot token, no interactivity Request URL.
            </li>
            <li>
              In the env file set the four values, then recreate the container (a restart does not
              re-read the env file):
              <CodeBlock>{'WEBHOOK_URL=https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXX\nWEBHOOK_FORMAT=slack\nSLACK_ACTIONABLE=true\nAPP_BASE_URL=https://approvals.example.com'}</CodeBlock>
            </li>
            <li>
              Request an app and confirm the message posts with buttons. No inbound endpoint is
              required — the buttons are deep links, so only the{' '}
              <span className="font-medium text-gray-800">approver's browser</span> reaches the
              tool. If the UI is LAN-only, approvers need the LAN or VPN.
            </li>
          </ol>
          <p className="text-gray-600">
            <span className="font-medium text-gray-800">Why deep links rather than deciding inside
            Slack.</span> A Slack interaction callback arrives where no signed-in user exists — the
            signature proves the workspace, not the person — so authority had to come from a
            separate approver list, which drifted from Omnissa Access group membership and failed{' '}
            <span className="font-medium text-gray-800">open</span>: revoking someone in Access left
            their Slack buttons working. Opening the tool means one set of roles governs every
            decision. It also removes the inbound endpoint and its shared secret.
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
              that stay pending longer than N days (checked hourly). They accept the same optional
              application-name pattern and Access group as a match rule — leave both blank, which
              is the usual case, to expire every stale request.
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

        <HelpSection title="Approval Chains">
          <p>
            Chains are managed on the <span className="font-medium text-gray-800">Chains</span>{' '}
            page (admins only). A chain requires <span className="font-medium text-gray-800">
            sequential</span> approval by different stages before a request reaches Omnissa Access —
            an app matching the chain's app name pattern and/or Access group needs every stage's
            approval, in order, rather than any one approver deciding it outright.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              A matched request is exempt from Auto-Approval Rules — a chain exists specifically to
              require sequential human judgment, so an auto-rule is never allowed to decide it on
              arrival. If both a chain and an auto-rule could match the same request, the chain wins.
            </li>
            <li>
              Each stage requires <span className="font-medium text-gray-800">anyone
              holding a role</span> (Admin, Approver, Viewer or Auditor),{' '}
              <span className="font-medium text-gray-800">anyone in a specific Access group</span>{' '}
              — read the group's id from the same place role-mapping ids come from,{' '}
              <Code>GET /api/auth/claims</Code> after signing in — or{' '}
              <span className="font-medium text-gray-800">one named person</span>, matched on the
              username or email they sign in with.
            </li>
            <li>
              Approving a non-final stage never contacts Omnissa Access — the request stays pending
              and moves to the next stage. Rejecting at{' '}
              <span className="font-medium text-gray-800">any</span> stage rejects the whole request
              immediately, the same as an ordinary decline.
            </li>
            <li>
              Admins may always decide any stage of any chain (the same break-glass precedent as
              elsewhere in this tool) — useful for unsticking a chain whose stage requirement can no
              longer be satisfied (e.g. an Access group was deleted).
            </li>
            <li>
              A chain with no stages configured is never matched — it would create a request nobody
              is eligible to decide, so it's skipped rather than routed to it.
            </li>
          </ul>
          <p className="font-medium text-gray-800">Notifications</p>
          <p>
            Whoever is eligible for the current stage is pushed a Hub Notification when a request
            first enters a chain and after every stage advance, if Hub Notifications is available on
            your tenant. For a role-based stage, recipients are everyone in the Access group(s)
            mapped to that role in <EnvVar name="OMNISSA_ROLE_MAP" /> — no separate configuration
            needed. These notifications are informational only; deciding a request always happens by
            signing in to this tool, never from an action button on the notification itself.
          </p>
          <p className="font-medium text-gray-800">What's not here yet</p>
          <p>
            A stage with nobody acting on it is covered only by the ordinary whole-request expiry
            auto-rule, the same as an unstaged pending request — there is no separate per-stage
            timeout or escalation.
          </p>
          <p className="font-medium text-gray-800">Naming one person</p>
          <p>
            A stage set to <span className="font-medium text-gray-800">Specific person</span> is
            matched against the acting session's own identity — their username or email as they
            sign in. Unlike an Access group stage, this works for local accounts too. It is also
            the narrowest option, and therefore the only one that becomes undecidable when that
            person leaves or changes how they sign in; administrators can always decide any stage,
            which is what stops that being a dead end. Prefer a role or a group wherever a team,
            rather than a named individual, is what you actually mean.
          </p>
          <p>
            Every chain decision appears in the{' '}
            <span className="font-medium text-gray-800">Audit</span> tab —{' '}
            <span className="font-medium text-gray-800">chain-matched</span> when a request is routed
            into a chain, and <span className="font-medium text-gray-800">stage-approved</span> for
            each stage along the way. The final stage's decision is audited exactly like any other
            approval or rejection.
          </p>
        </HelpSection>

        <HelpSection title="Ownership and Escalation">
          <p>
            A request nobody attends to used to have exactly one outcome — the expiry rule
            auto-rejected it after N days, and the requester found out by being denied. Ownership
            and escalation are the missing middle.
          </p>
          <p className="font-medium text-gray-800">Claim, Assign, Release</p>
          <p>
            On a pending request, an approver can <span className="font-medium text-gray-800">
            Claim</span> it (take visible ownership), <span className="font-medium text-gray-800">
            Assign</span> it to a named approver, or <span className="font-medium text-gray-800">
            Release</span> it back to the pool. The owner appears as a badge in the queue.
          </p>
          <p>
            <span className="font-medium text-gray-800">Ownership is advisory and never
            authorization.</span> Any approver can decide any request no matter who holds it. A
            claim that could block a decision would make a request undecidable the moment its owner
            went on leave — a convenience turned into an outage — so the Review button stays
            available to everyone who may decide. Claiming does not steal a claim someone else
            holds, but any approver may release any claim, so nothing stays welded to somebody who
            has left.
          </p>
          <p>
            Assigning carries no obligation either: escalation still fires on schedule, and an
            assignment nobody actions is released automatically just like an abandoned self-claim.
            The approver list in the Assign picker is resolved live from the Access groups mapped
            to the Approver and Admin roles in <EnvVar name="OMNISSA_ROLE_MAP" /> — there is no
            separate approver list to maintain.
          </p>
          <p className="font-medium text-gray-800">Escalation</p>
          <p>
            Escalation is configured on an <span className="font-medium text-gray-800">expiry
            rule</span>, under the optional Escalation section, so one rule reads "nudge after 4
            hours, then auto-reject after 3 days". When a matching request has been pending that
            long, the tool notifies the chat channel and pushes a Hub Notification to the approvers
            themselves. It honours the rule's own application-name pattern and group, so you can
            escalate Finance apps without escalating everything.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              Each request escalates <span className="font-medium text-gray-800">once</span>. A
              request decided while the sweep is running is skipped rather than nudged.
            </li>
            <li>
              <span className="font-medium text-gray-800">Escalate now</span> on the request detail
              page skips the remaining timer. It is the only way to confirm escalation is wired up
              correctly without waiting, and it is audited as you rather than as the timer.
            </li>
            <li>
              An unactioned claim is released after its own interval — an abandoned claim reads as
              "handled" to everyone else, which is worse than no claim at all.
            </li>
            <li>
              The audit trail records what actually happened, including when nothing was configured
              to receive the nudge. It never claims a notification it did not send.
            </li>
          </ul>
          <p>
            Escalation runs on its own background thread, separate from the sweeps that expire
            time-bound access, so a slow or unreachable tenant can only ever delay escalation
            itself. Its health appears alongside the other scheduled jobs under Health and
            Monitoring.
          </p>
        </HelpSection>

        <HelpSection title="Health and Monitoring">
          <p>
            Two endpoints, because "the container is down" and "something it depends on is
            unhealthy" call for different responses:
          </p>
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <Code>/actuator/health</Code> — <span className="font-medium text-gray-800">liveness
              only</span>, public. Deliberately ignores dependencies: it is what the Docker health
              check, CasaOS and the reverse proxy watch, and CasaOS{' '}
              <span className="font-medium text-gray-800">recreates the container</span> when it
              fails. An Omnissa Access outage must not take down a healthy service.
            </li>
            <li>
              <Code>/api/health/deps</Code> — public, aggregate word only:{' '}
              <Code>{'{"status":"UP"}'}</Code>, <Code>DEGRADED</Code> or <Code>DOWN</Code>. No
              tenant name, no error text, no counts. Always returns HTTP 200 so a plain HTTP
              monitor stays green and only a keyword monitor trips.
            </li>
            <li>
              <Code>/api/health/dependencies</Code> — signed in, full per-component detail.
            </li>
          </ul>
          <p>What turns the status to DEGRADED:</p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <span className="font-medium text-gray-800">Omnissa Access</span> — configured but a
              token fetch fails. Not-yet-configured is setup, not a fault.
            </li>
            <li>
              <span className="font-medium text-gray-800">Scheduler</span> — a JIT sweep has not run
              for about 5 minutes, or the hourly rule sweep for about 3 hours. The most valuable
              check here: all scheduled jobs share one thread, so if the sweeps wedge, time-bound
              access <span className="font-medium text-gray-800">silently never expires</span> while
              everything else looks healthy.
            </li>
            <li>
              <span className="font-medium text-gray-800">Approval drift</span> — Omnissa Access is
              holding requests this queue has no pending record of. Use{' '}
              <span className="font-medium text-gray-800">Pull from Access</span> on the queue to
              import them; until they are decided the requesters wait indefinitely.
            </li>
            <li>
              <span className="font-medium text-gray-800">Notifications</span> — three consecutive
              webhook delivery failures.
            </li>
          </ul>
          <p className="text-amber-800 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
            <span className="font-medium">Note —</span> a healthy{' '}
            <span className="font-medium">Notifications</span> status means the webhook endpoint
            accepted the request, not that the message reached the channel. Slack posts
            synchronously, so success there is real; Power Automate returns{' '}
            <Code>202 Accepted</Code> — queued — and the flow can still fail afterwards. Teams
            delivery is not confirmed by this check.
          </p>
          <p>
            For <span className="font-medium text-gray-800">Uptime Kuma</span>, use two monitors:
            an HTTP(s) monitor on <Code>/actuator/health</Code> for outages, and an{' '}
            <span className="font-medium text-gray-800">HTTP(s) Keyword</span> monitor on{' '}
            <Code>/api/health/deps</Code> matching <Code>"status":"UP"</Code> for warnings — routed
            to different notifications.
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

      <BackToTop />
    </div>
  )
}
