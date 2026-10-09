import AppShell from '../../AppShell'
import DashboardHero from './DashboardHero'
import RecentProjects from './RecentProjects'
import './dashboard.css'

/** Signed-in home: start a new project, or jump back into a recent one. */
export default function Dashboard() {
  return (
    <AppShell active="home" surface="plain">
      <div className="db">
        <DashboardHero />
        <RecentProjects />
      </div>
    </AppShell>
  )
}
