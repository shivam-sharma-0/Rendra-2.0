import { type MessageRole } from '../../api/projects'

export const TIMELINE_EVENT_TYPES = new Set(['THINKING', 'TOOL_CALL', 'TOOL_RESULT', 'ASK_USER', 'MESSAGE', 'STATUS'])

export const PAGE_SIZE = 50

export type TimelineEvent = { runId?: string; seq?: number; content?: string; data?: unknown }

export type Tab = 'preview' | 'files' | 'code' | 'history' | 'details' | 'analytics' | 'security' | 'database' | 'thinking'

export type LayoutMode = 'split' | 'chat-only' | 'preview-only'

export type Quote = { role: MessageRole; text: string }

export type Device = 'desktop' | 'tablet' | 'mobile'
