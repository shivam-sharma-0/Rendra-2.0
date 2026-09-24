import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import ChatsPage from './pages/Chats'
import Dashboard from './pages/Dashboard'
import Landing from './pages/Landing'
import OAuthCallback from './pages/OAuthCallback'
import ProjectPage from './pages/Project'
import ProjectsPage from './pages/Projects'
import SettingsWorkspace from './pages/SettingsWorkspace'
import Workspace from './pages/Workspace'

/** Root: the marketing landing for guests; signed-in users get the dashboard as their home. */
function Home() {
  const { isAuthenticated, loading } = useAuth()
  if (loading) return null // wait for the silent refresh before deciding
  return isAuthenticated ? <Navigate to="/dashboard" replace /> : <Landing />
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/dashboard" element={<Dashboard />} />
      <Route path="/projects" element={<ProjectsPage />} />
      <Route path="/chats" element={<ChatsPage />} />
      <Route path="/settings/workspace" element={<SettingsWorkspace />} />
      <Route path="/project/:id" element={<ProjectPage />} />
      <Route path="/app" element={<Workspace />} />
      <Route path="/oauth/callback" element={<OAuthCallback />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
