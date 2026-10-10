import { useEffect, useRef } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../../auth/AuthContext'
import { NeuButton } from '../../../components/neu'
import { cx } from '../../../lib/cx'
import AppShell from '../../AppShell'
import PageHeader from '../../ui/PageHeader'
import { AppearanceSection, BillingSection, MembersSection, NotificationsSection, ProfileSection } from './sections'
import './settings.css'

const GROUPS = ['Personal', 'Workspace'] as const

const SECTIONS = [
  { id: 'profile', label: 'Profile', group: 'Personal', sub: 'Your account details.', Body: ProfileSection },
  { id: 'notifications', label: 'Notifications', group: 'Personal', sub: 'What Rendra tells you about, and where.', Body: NotificationsSection },
  { id: 'appearance', label: 'Appearance', group: 'Personal', sub: 'How Rendra looks for you.', Body: AppearanceSection },
  { id: 'members', label: 'Members', group: 'Workspace', sub: 'People in this workspace and what they can do.', Body: MembersSection },
  { id: 'billing-and-usage', label: 'Billing & usage', group: 'Workspace', sub: 'Your plan and how much AI you have used today.', Body: BillingSection },
] as const

/** Settings at /settings/<section>: a section list beside the open section. */
export default function Settings() {
  const { pathname } = useLocation()
  const nav = useNavigate()
  const { signOut } = useAuth()
  const current = SECTIONS.find((s) => pathname.startsWith(`/settings/${s.id}`)) ?? SECTIONS[0]
  const { Body } = current
  const navRef = useRef<HTMLElement>(null)

  // On narrow screens the section list scrolls sideways; keep the open section in view.
  useEffect(() => {
    const list = navRef.current
    const item = list?.querySelector<HTMLElement>('.is-on')
    if (!list || !item || list.scrollWidth <= list.clientWidth) return
    const box = list.getBoundingClientRect(), it = item.getBoundingClientRect()
    list.scrollLeft += it.left - box.left - (box.width - it.width) / 2
  }, [current.id])

  return (
    <AppShell surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader title="Settings" description="Your account and this workspace." />
          <div className="st">
            <nav className="st-nav" aria-label="Settings sections" ref={navRef}>
              {GROUPS.map((group) => (
                <div key={group} className="st-nav__group">
                  <small>{group}</small>
                  {SECTIONS.filter((s) => s.group === group).map((s) => (
                    <Link key={s.id} to={`/settings/${s.id}`} className={cx('st-nav__item', s.id === current.id && 'is-on')} aria-current={s.id === current.id ? 'page' : undefined}>
                      {s.label}
                    </Link>
                  ))}
                </div>
              ))}
              <NeuButton size="sm" className="st-logout" onClick={() => { signOut(); nav('/') }}>Log out</NeuButton>
            </nav>
            <section className="st-main neu-panel" aria-labelledby="st-title">
              <header className="st-head">
                <h2 id="st-title">{current.label}</h2>
                <p>{current.sub}</p>
              </header>
              <Body />
            </section>
          </div>
        </div>
      </div>
    </AppShell>
  )
}
