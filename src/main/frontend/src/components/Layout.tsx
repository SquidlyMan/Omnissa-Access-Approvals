import { useState } from 'react'
import { Outlet, NavLink } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

const desktopLinkClass = ({ isActive }: { isActive: boolean }) =>
  `hidden md:block text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`

const mobileLinkClass = ({ isActive }: { isActive: boolean }) =>
  `block w-full text-sm py-2 px-4 transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`

export default function Layout() {
  const { user } = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)

  const closeMenu = () => setMenuOpen(false)

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top nav */}
      <nav className="bg-omnissa text-white shadow-md">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 flex h-14 items-center justify-between">
          <div className="flex items-center gap-6 min-w-0">
            <span className="font-semibold text-base md:text-lg tracking-tight truncate max-w-[60vw] md:max-w-none">
              Access Approval Tool for Omnissa
            </span>
            <NavLink to="/dashboard" className={desktopLinkClass}>
              Dashboard
            </NavLink>
            <NavLink to="/queue" className={desktopLinkClass}>
              Queue
            </NavLink>
            <NavLink to="/rules" className={desktopLinkClass}>
              Rules
            </NavLink>
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
            <NavLink to="/dashboard" onClick={closeMenu} className={mobileLinkClass}>
              Dashboard
            </NavLink>
            <NavLink to="/queue" onClick={closeMenu} className={mobileLinkClass}>
              Queue
            </NavLink>
            <NavLink to="/rules" onClick={closeMenu} className={mobileLinkClass}>
              Rules
            </NavLink>
            <NavLink to="/help" onClick={closeMenu} className={mobileLinkClass}>
              Help
            </NavLink>
            <span className="block w-full text-sm py-2 px-4 text-white/60">
              {user?.name || user?.username}
            </span>
            <a href="/logout" className="block w-full text-sm py-2 px-4 hover:bg-white/10 transition-colors">
              Sign out
            </a>
          </div>
        )}
      </nav>

      {/* Page content */}
      <main className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6">
        <Outlet />
      </main>
    </div>
  )
}
