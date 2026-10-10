import { type AgentRun, type AgentStep, type PreviewDraftEvent, type PreviewStatus, type Project, type RequirementSession } from '../../../api/projects'
import type { Device, Tab } from '../workspaceTypes'
import { CodePanel } from './CodePanel'
import { DetailsPanel } from './DetailsPanel'
import { FilesPanel } from './FilesPanel'
import { HistoryPanel } from './HistoryPanel'
import { AnalyticsPanel, DatabasePanel, SecurityPanel } from './InsightPanels'
import { PreviewPanel } from './PreviewPanel'
import { ThinkingPanel } from './ThinkingPanel'
import './panels.css'

type PanelContentProps = {
  tab: Tab
  projectId: string
  project: Project | null
  /** The run currently building, if any; Code and Files show its staged changes. */
  activeRunId: string | null
  runs: AgentRun[]
  selectedRun?: AgentRun
  steps: AgentStep[]
  requirements: RequirementSession | null
  thinking: { reasoning: string; seconds: number } | null
  onCloseThinking: () => void
  onRestoreRun: (runId: string) => void
  rollingBackId: string | null
  preview: { update: PreviewStatus | null; draftUpdate: PreviewDraftEvent | null; reloadRequest: number; device: Device; onUrlChange: (url: string | null) => void }
}

/** The body of the panel for the selected tab (Preview is the fallback). */
export function PanelContent({ tab, projectId, project, activeRunId, runs, selectedRun, steps, requirements, thinking, onCloseThinking, onRestoreRun, rollingBackId, preview }: PanelContentProps) {
  return (
    <div key={tab} className="pv-root">
      {tab === 'thinking' && thinking ? <ThinkingPanel reasoning={thinking.reasoning} seconds={thinking.seconds} onBack={onCloseThinking} />
        : tab === 'files' ? <FilesPanel projectId={projectId} activeRunId={activeRunId} />
        : tab === 'code' ? <CodePanel projectId={projectId} activeRunId={activeRunId} />
        : tab === 'history' ? <HistoryPanel runs={runs} onRestore={onRestoreRun} rollingBackId={rollingBackId} />
        : tab === 'details' ? <DetailsPanel run={selectedRun} steps={steps} stage={project?.lifecycleStage} requirements={requirements} />
        : tab === 'analytics' ? <AnalyticsPanel projectId={projectId} active />
        : tab === 'security' ? <SecurityPanel projectId={projectId} active />
        : tab === 'database' ? <DatabasePanel projectId={projectId} />
        : <PreviewPanel project={project} update={preview.update} draftUpdate={preview.draftUpdate} reloadRequest={preview.reloadRequest} device={preview.device} onUrlChange={preview.onUrlChange} />}
    </div>
  )
}
