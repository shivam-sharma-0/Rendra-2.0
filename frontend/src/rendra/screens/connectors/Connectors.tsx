import { useStoredState } from '../../../lib/useStoredState'
import AppShell from '../../AppShell'
import PageHeader from '../../ui/PageHeader'
import ConnectorCard from './ConnectorCard'
import { CONNECTORS, DEFAULT_CONNECTED } from './content'
import './connectors.css'

/**
 * Integration catalogue grouped into Connected and Popular.
 * There is no connectors backend yet: connect / disconnect is remembered in this browser only,
 * so the screen is interactive while each provider's real OAuth is still to be built.
 */
export default function Connectors() {
  const [connected, setConnected] = useStoredState<ReadonlySet<string>>(
    'rendra_connectors',
    (raw) => { try { return new Set(raw ? (JSON.parse(raw) as string[]) : DEFAULT_CONNECTED) } catch { return new Set(DEFAULT_CONNECTED) } },
    (set) => JSON.stringify([...set]),
  )

  const toggle = (id: string) => {
    const next = new Set(connected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setConnected(next)
  }

  const groups = [
    { title: 'Connected', items: CONNECTORS.filter((c) => connected.has(c.id)) },
    { title: 'Popular', items: CONNECTORS.filter((c) => !connected.has(c.id)) },
  ].filter((g) => g.items.length > 0)

  return (
    <AppShell active="connectors" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader title="Connectors" description="Connect a service once — every project in this workspace can use it." />
          {groups.map((g) => (
            <section key={g.title} className="cn-group" aria-labelledby={`cn-${g.title}`}>
              <h2 id={`cn-${g.title}`}>{g.title}</h2>
              <div className="cn-grid">
                {g.items.map((c) => <ConnectorCard key={c.id} connector={c} connected={connected.has(c.id)} onToggle={() => toggle(c.id)} />)}
              </div>
            </section>
          ))}
        </div>
      </div>
    </AppShell>
  )
}
