import type { Device, Tab } from '../workspaceTypes'

export type PanelTabDef = { id: Tab; label: string; icon: string }

/** The panel's views, grouped as they appear in the icon rail: the app, its build, its insights. */
export const PANEL_TAB_GROUPS: PanelTabDef[][] = [
  [
    { id: 'preview', label: 'Preview', icon: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z' },
    { id: 'code',    label: 'Code',    icon: 'M16 18l6-6-6-6M8 6l-6 6 6 6' },
    { id: 'files',   label: 'Files',   icon: 'M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z' },
  ],
  [
    { id: 'details', label: 'Details', icon: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01' },
    { id: 'history', label: 'History', icon: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2' },
  ],
  [
    { id: 'analytics', label: 'Analytics', icon: 'M3 3v18h18M18 17l-5-5-4 4-3-3' },
    { id: 'security',  label: 'Security',  icon: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z' },
    { id: 'database',  label: 'Database',  icon: 'M12 2C8.7 2 6 3.8 6 6s2.7 4 6 4 6-1.8 6-4-2.7-4-6-4zM6 10v4c0 2.2 2.7 4 6 4s6-1.8 6-4v-4M6 14v4c0 2.2 2.7 4 6 4s6-1.8 6-4v-4' },
  ],
]

export const WS_DEVICES: { id: Device; label: string; icon: string }[] = [
  { id: 'desktop', label: 'Desktop', icon: 'M20 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2zM12 17v4M8 21h8' },
  { id: 'tablet',  label: 'Tablet',  icon: 'M18 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM12 18h.01' },
  { id: 'mobile',  label: 'Mobile',  icon: 'M12 18h.01M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z' },
]

export const PANEL_TITLES: Record<Tab, string> = {
  preview: 'Preview', files: 'Files', code: 'Code', details: 'Details', history: 'History',
  analytics: 'Analytics', security: 'Security', database: 'Database', thinking: 'Reasoning',
}
