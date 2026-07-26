import { useState } from 'react'
import { Outlet, NavLink } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'
import { canAdminister, canViewAudit, canViewQueue, roleLabel } from '../lib/permissions'
import ChangePasswordDialog from './ChangePasswordDialog'

const desktopLinkClass = ({ isActive }: { isActive: boolean }) =>
  `hidden md:block text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`

const mobileLinkClass = ({ isActive }: { isActive: boolean }) =>
  `block w-full text-sm py-2 px-4 transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`

export default function Layout() {
  const { user } = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [passwordChanged, setPasswordChanged] = useState(false)

  const closeMenu = () => setMenuOpen(false)

  // Auditors only get the audit trail — send them straight to that tab and hide
  // the pages they cannot open (#52).
  const showQueueLinks = canViewQueue(user)
  const showAuditLink = !showQueueLinks && canViewAudit(user)
  const showUsersLink = canAdminister(user)
  const role = roleLabel(user)

  // Self-service password change lives here rather than on the admin-only
  // Users page (#58): every local account needs it, including viewers and
  // auditors who never see an admin page. An OIDC session has no local
  // password — that credential lives in Omnissa Access.
  const showPasswordChange = user?.loginType === 'local'

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top nav */}
      <nav className="bg-omnissa text-white shadow-md">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 flex h-14 items-center justify-between">
          <div className="flex items-center gap-6 min-w-0">
            <span className="font-semibold text-base md:text-lg tracking-tight truncate max-w-[60vw] md:max-w-none">
              Access Approval Tool for Omnissa
            </span>
            {showQueueLinks && (
              <>
                <NavLink to="/dashboard" className={desktopLinkClass}>
                  Dashboard
                </NavLink>
                <NavLink to="/queue" className={desktopLinkClass}>
                  Queue
                </NavLink>
                <NavLink to="/rules" className={desktopLinkClass}>
                  Rules
                </NavLink>
              </>
            )}
            {showAuditLink && (
              <NavLink to="/queue?state=audit" className={desktopLinkClass}>
                Audit
              </NavLink>
            )}
            {showUsersLink && (
              <NavLink to="/users" className={desktopLinkClass}>
                Users
              </NavLink>
            )}
          </div>
          <div className="hidden md:flex items-center gap-3 text-sm">
            <NavLink
              to="/help"
              className={({ isActive }) =>
                `text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`
              }
            >
              Help
            </NavLink>
            <span className="text-white/80">{user?.name || user?.username}</span>
            {role && (
              <span
                className="rounded-full bg-white/15 px-2 py-0.5 text-xs font-medium text-white/90"
                title={`Signed in with ${role} permissions`}
              >
                {role}
              </span>
            )}
            {showPasswordChange && (
              <button
                type="button"
                onClick={() => { setPasswordChanged(false); setPasswordOpen(true) }}
                className="bg-white/10 hover:bg-white/20 px-3 py-1 rounded transition-colors"
              >
                Change password
              </button>
            )}
            <a
              href="/logout"
              className="bg-white/10 hover:bg-white/20 px-3 py-1 rounded transition-colors"
            >
              Sign out
            </a>
          </div>
          {/* Mobile hamburger */}
          <button
            type="button"
            onClick={() => setMenuOpen(o => !o)}
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            aria-expanded={menuOpen}
            className="md:hidden shrink-0 p-2 -mr-2 rounded hover:bg-white/10 transition-colors"
          >
            {menuOpen ? (
              <svg className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            ) : (
              <svg className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            )}
          </button>
        </div>

        {/* Mobile menu panel */}
        {menuOpen && (
          <div className="md:hidden border-t border-white/10 pb-2 bg-omnissa">
            {showQueueLinks && (
              <>
                <NavLink to="/dashboard" onClick={closeMenu} className={mobileLinkClass}>
                  Dashboard
                </NavLink>
                <NavLink to="/queue" onClick={closeMenu} className={mobileLinkClass}>
                  Queue
                </NavLink>
                <NavLink to="/rules" onClick={closeMenu} className={mobileLinkClass}>
                  Rules
                </NavLink>
              </>
            )}
            {showAuditLink && (
              <NavLink to="/queue?state=audit" onClick={closeMenu} className={mobileLinkClass}>
                Audit
              </NavLink>
            )}
            {showUsersLink && (
              <NavLink to="/users" onClick={closeMenu} className={mobileLinkClass}>
                Users
              </NavLink>
            )}
            <NavLink to="/help" onClick={closeMenu} className={mobileLinkClass}>
              Help
            </NavLink>
            <span className="block w-full text-sm py-2 px-4 text-white/60">
              {user?.name || user?.username}{role && ` · ${role}`}
            </span>
            {showPasswordChange && (
              <button
                type="button"
                onClick={() => { closeMenu(); setPasswordChanged(false); setPasswordOpen(true) }}
                className="block w-full text-left text-sm py-2 px-4 hover:bg-white/10 transition-colors"
              >
                Change password
              </button>
            )}
            <a href="/logout" className="block w-full text-sm py-2 px-4 hover:bg-white/10 transition-colors">
              Sign out
            </a>
          </div>
        )}
      </nav>

      {/* Page content */}
      <main className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6">
        {passwordChanged && (
          <div className="mb-4 flex items-start justify-between gap-4 rounded-lg bg-green-50 border border-green-200 px-4 py-2 text-sm text-green-800">
            <span>Your password was changed. Use the new one the next time you sign in.</span>
            <button
              onClick={() => setPasswordChanged(false)}
              className="text-green-700 hover:text-green-900 shrink-0"
            >
              Dismiss
            </button>
          </div>
        )}
        <Outlet />
      </main>

      {passwordOpen && (
        <ChangePasswordDialog
          onClose={() => setPasswordOpen(false)}
          onDone={() => { setPasswordOpen(false); setPasswordChanged(true) }}
        />
      )}
    </div>
  )
}
