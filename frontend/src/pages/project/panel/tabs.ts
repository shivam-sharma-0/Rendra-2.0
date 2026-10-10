import type { Device, Tab } from '../workspaceTypes'
import type { AppIconName } from '../../../components/icons'

export type PanelTabDef = { id: Tab; label: string; icon: AppIconName }

/** The panel's views, grouped as they appear in the icon rail: the app, its build, its insights. */
export const PANEL_TAB_GROUPS: PanelTabDef[][] = [
  [
    { id: 'preview', label: 'Preview', icon: 'preview' },
    { id: 'code',    label: 'Code',    icon: 'code' },
    { id: 'files',   label: 'Files',   icon: 'files' },
  ],
  [
    { id: 'details', label: 'Details', icon: 'details' },
    { id: 'history', label: 'History', icon: 'history' },
  ],
  [
    { id: 'analytics', label: 'Analytics', icon: 'analytics' },
    { id: 'security',  label: 'Security',  icon: 'security' },
    { id: 'database',  label: 'Database',  icon: 'database' },
  ],
]

export const PANEL_TABS = PANEL_TAB_GROUPS.flat()

export const WS_DEVICES: { id: Device; label: string; icon: AppIconName }[] = [
  { id: 'desktop', label: 'Desktop', icon: 'desktop' },
  { id: 'tablet',  label: 'Tablet',  icon: 'tablet' },
  { id: 'mobile',  label: 'Mobile',  icon: 'mobile' },
]

export const PANEL_TITLES: Record<Tab, string> = {
  preview: 'Preview', files: 'Files', code: 'Code', details: 'Details', history: 'History',
  analytics: 'Analytics', security: 'Security', database: 'Database', thinking: 'Reasoning',
}
