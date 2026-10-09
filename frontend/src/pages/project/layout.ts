import type { LayoutMode } from './workspaceTypes'

const DEFAULT_CHAT_WIDTH = 440

export const MIN_CHAT_WIDTH = 360

export const MIN_PANEL_WIDTH = 380 // keep in step with .ws-panel min-width (workspace.css)

export const COLLAPSE_THRESHOLD = 140

export function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

export function readStoredLayoutMode(): LayoutMode {
  try {
    const value = localStorage.getItem('pw-layout-mode')
    return value === 'chat-only' || value === 'preview-only' || value === 'split' ? value : 'split'
  } catch { return 'split' }
}

export function readStoredChatWidth(): number {
  try {
    const value = Number(localStorage.getItem('pw-chat-width'))
    return Number.isFinite(value) && value > 0 ? value : DEFAULT_CHAT_WIDTH
  } catch { return DEFAULT_CHAT_WIDTH }
}
