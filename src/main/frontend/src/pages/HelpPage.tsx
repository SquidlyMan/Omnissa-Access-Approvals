interface HelpSectionProps {
  title: string
  children: React.ReactNode
}

function HelpSection({ title, children }: HelpSectionProps) {
  return (
    <section className="bg-white rounded-xl border border-gray-200 p-6">
      <h2 className="font-semibold text-gray-800 text-lg mb-3">{title}</h2>
      <div className="text-sm text-gray-600 space-y-3">{children}</div>
    </section>
  )
}

function EnvVar({ name }: { name: string }) {
  return <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">{name}</code>
}

export default function HelpPage() {
  return (
    <div>
      <h1 className="text-2xl font-semibold text-gray-900 mb-6">Help</h1>

      <div className="space-y-6 max-w-4xl">
        <HelpSection title="Overview">
          <p>
            The Omnissa Access Approval Tool receives approval callouts from Omnissa Access whenever
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
              launch. If declined, the application is deactivated and appears in this tool's
              Deactivated list.
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
          </ul>
        </HelpSection>

        <HelpSection title="Admin Sign-In Options">
          <ul className="list-disc pl-5 space-y-2">
            <li>
              <span className="font-medium text-gray-800">Local admin</span> — a username/password account
              defined by the container environment values <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_USERNAME" />{' '}
              and <EnvVar name="OMNISSA_BOOTSTRAP_ADMIN_PASSWORD" />.
            </li>
            <li>
              <span className="font-medium text-gray-800">Sign in with Omnissa Access</span> — OIDC single
              sign-on, configured with the <EnvVar name="OMNISSA_ADMIN_OAUTH_*" /> container
              environment values.
            </li>
          </ul>
          <p>
            Setting <EnvVar name="OMNISSA_AUTH_LOCAL_LOGIN_DISABLED" />=<code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">true</code>{' '}
            forces OAuth-only sign-in: the local username/password form is hidden and only "Sign in
            with Omnissa Access" is available.
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
        </HelpSection>

        <HelpSection title="Webhook Notifications">
          <p>
            Set the <EnvVar name="WEBHOOK_URL" /> container environment value to POST a notification
            for each new access request. Set <EnvVar name="WEBHOOK_FORMAT" /> to{' '}
            <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">generic</code>{' '}
            (structured JSON),{' '}
            <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">slack</code>, or{' '}
            <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">teams</code>{' '}
            (simple text payloads for incoming-webhook integrations). Restart the container to apply.
          </p>
        </HelpSection>

        <HelpSection title="Auto-Approval Rules">
          <p>
            Rules are managed on the <span className="font-medium text-gray-800">Rules</span> page.
          </p>
          <ul className="list-disc pl-5 space-y-1">
            <li>
              <span className="font-medium text-gray-800">Match rules</span> auto-approve or
              auto-reject requests on arrival, by application name pattern (
              <code className="text-xs bg-gray-100 text-gray-800 rounded px-1.5 py-0.5">*</code>{' '}
              wildcard) and/or Access group membership.
            </li>
            <li>
              <span className="font-medium text-gray-800">Expiry rules</span> auto-reject requests
              that stay pending longer than N days (checked hourly).
            </li>
          </ul>
          <p>
            All rule decisions appear in the Audit trail as{' '}
            <span className="font-medium text-gray-800">auto-approved</span> or{' '}
            <span className="font-medium text-gray-800">auto-rejected</span>.
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
            On the <span className="font-medium text-gray-800">Queue</span> page, click{' '}
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
          <a
            href="/api/logs/bundle"
            download
            className="inline-block bg-omnissa text-white rounded-lg px-4 py-2 text-sm font-medium hover:bg-omnissa-dark transition-colors"
          >
            Download Log Bundle (last hour)
          </a>
          <p>
            Application logs can also be forwarded to a syslog server. Set the{' '}
            <EnvVar name="SYSLOG_HOST" /> container environment value (and optionally{' '}
            <EnvVar name="SYSLOG_PORT" />, default 514, UDP), then restart the container to apply.
          </p>
        </HelpSection>
      </div>
    </div>
  )
}
