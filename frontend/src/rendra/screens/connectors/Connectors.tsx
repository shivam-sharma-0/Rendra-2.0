import AppShell from '../../AppShell'
import PageHeader from '../../ui/PageHeader'
import ConnectorCard from './ConnectorCard'
import { CONNECTORS } from './content'
import './connectors.css'

/**
 * Integration catalogue. No connector has a backend yet, so none is shown as connected: each card says
 * "Coming soon" instead of pretending a GitHub sync or Slack alert exists. Supabase is connected per project.
 */
export default function Connectors() {
  return (
    <AppShell active="connectors" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader title="Connectors"
            description="These integrations are coming soon. Supabase is already available: connect it from a project." />
          <section className="cn-group" aria-labelledby="cn-soon">
            <h2 id="cn-soon">Coming soon</h2>
            <div className="cn-grid">
              {CONNECTORS.map((c) => <ConnectorCard key={c.id} connector={c} />)}
            </div>
          </section>
        </div>
      </div>
    </AppShell>
  )
}
