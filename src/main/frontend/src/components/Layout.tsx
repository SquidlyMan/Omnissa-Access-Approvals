import { Outlet, NavLink } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'

export default function Layout() {
  const { user } = useAuth()

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Top nav */}
      <nav className="bg-omnissa text-white shadow-md">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 flex h-14 items-center justify-between">
          <div className="flex items-center gap-6">
            <span className="font-semibold text-lg tracking-tight">Access Approval Tool for Omnissa</span>
            <NavLink
              to="/dashboard"
              className={({ isActive }) =>
                `text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`
              }
            >
              Dashboard
            </NavLink>
            <NavLink
              to="/queue"
              className={({ isActive }) =>
                `text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`
              }
            >
              Queue
            </NavLink>
            <NavLink
              to="/rules"
              className={({ isActive }) =>
                `text-sm px-3 py-1 rounded transition-colors ${isActive ? 'bg-white/20' : 'hover:bg-white/10'}`
              }
            >
              Rules
            </NavLink>
          </div>
          <div className="flex items-center gap-3 text-sm">
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
        </div>
      </nav>

      {/* Page content */}
      <main className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8 py-6">
        <Outlet />
      </main>
    </div>
  )
}
