import { Component, type ErrorInfo, type ReactNode } from 'react'

type Props = { children: ReactNode; fallback?: ReactNode }
type State = { error: Error | null }

/**
 * Catches render-time crashes in its subtree and shows a recoverable fallback instead of a blank
 * white screen. Wrap the app root (and any independently-recoverable region). React error
 * boundaries must be class components; this is the one intentional class in the codebase.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Kept to the console for now; this is the single seam where a Sentry/monitoring hook would go.
    console.error('Unhandled UI error:', error, info.componentStack)
  }

  private reset = () => this.setState({ error: null })

  render(): ReactNode {
    if (!this.state.error) return this.props.children
    if (this.props.fallback) return this.props.fallback
    return (
      <div role="alert" style={fallbackStyle}>
        <div style={cardStyle}>
          <h1 style={{ font: "700 22px 'Bricolage Grotesque', system-ui, sans-serif", margin: 0 }}>
            Something went wrong
          </h1>
          <p style={{ color: '#6E6B74', fontSize: 14, lineHeight: 1.5, margin: 0 }}>
            An unexpected error interrupted this view. You can retry, or reload the page.
          </p>
          <div style={{ display: 'flex', gap: 10 }}>
            <button onClick={this.reset} style={primaryBtn}>Try again</button>
            <button onClick={() => window.location.reload()} style={ghostBtn}>Reload</button>
          </div>
        </div>
      </div>
    )
  }
}

const fallbackStyle = {
  minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24,
  background: '#F6F4EF', color: '#16151A',
} as const
const cardStyle = {
  width: 'min(420px, 100%)', display: 'flex', flexDirection: 'column', gap: 14,
  background: '#fff', border: '1px solid #E8E4DB', borderRadius: 20, padding: 28,
} as const
const primaryBtn = {
  height: 44, padding: '0 18px', borderRadius: 12, border: 0,
  background: '#16151A', color: '#F6F4EF', fontWeight: 600, cursor: 'pointer',
} as const
const ghostBtn = {
  height: 44, padding: '0 18px', borderRadius: 12, border: '1px solid #E3DFD6',
  background: '#fff', color: '#16151A', fontWeight: 600, cursor: 'pointer',
} as const
