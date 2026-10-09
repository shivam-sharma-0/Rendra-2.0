import { useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import ChatsDialog from './shell/ChatsDialog'
import Sidebar from './shell/Sidebar'
import TopBar from './shell/TopBar'
import type { NavId, PopoverControl, ShellPopover } from './shell/types'

export type { NavId } from './shell/types'

/**
 * Authenticated app shell: sidebar + top bar + a rounded main surface for `children`.
 * Shared by the dashboard, projects, chats, templates, connectors, notifications and
 * project screens, so the chrome is defined once. The pieces live in `./shell/`.
 */
export default function AppShell({ active, children }: { active?: NavId; children: ReactNode }) {
  const { pathname } = useLocation()
  const openProjectId = pathname.startsWith('/project/') ? pathname.split('/')[2] : null
  const [popover, setPopover] = useState<ShellPopover>('none')
  const [chatsOpen, setChatsOpen] = useState(false)

  const control = (id: Exclude<ShellPopover, 'none'>): PopoverControl => ({
    open: popover === id,
    onToggle: () => setPopover((cur) => (cur === id ? 'none' : id)),
    onClose: () => setPopover('none'),
  })

  return (
    <div className="kap h-screen flex bg-[var(--bg)] text-[var(--ink)] font-['Geist',system-ui,sans-serif] overflow-hidden">
      {popover !== 'none' && <div className="kap-scrim" onClick={() => setPopover('none')} />}
      {chatsOpen && <ChatsDialog onClose={() => setChatsOpen(false)} />}

      <Sidebar
        active={active}
        openProjectId={openProjectId}
        chatsOpen={chatsOpen}
        onOpenChats={() => setChatsOpen(true)}
        workspaceMenu={control('workspace')}
        accountMenu={control('account')}
      />

      <main className="kap-main">
        {!openProjectId && <TopBar active={active} notifications={control('notifications')} />}
        {children}
      </main>
    </div>
  )
}
