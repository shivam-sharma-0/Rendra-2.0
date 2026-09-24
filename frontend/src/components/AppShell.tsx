import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import Sidebar from './Sidebar'

/** Signed-in app shell: fixed sidebar + a scrollable content column. Guards for auth. */
export default function AppShell({ children }: { children: ReactNode }) {
  const { isAuthenticated, loading } = useAuth()
  if (loading) return null
  if (!isAuthenticated) return <Navigate to="/" replace />

  return (
    <div className="flex min-h-screen">
      <Sidebar />
      <main className="relative h-screen flex-1 overflow-y-auto">{children}</main>
    </div>
  )
}
