import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { NeuButton } from '../../components/neu'
import { EmptyState } from '../../components/ui/States'
import { cx } from '../../lib/cx'
import AppShell from '../../rendra/AppShell'
import { ChatColumn } from './chat/ChatColumn'
import { PublishDialog } from './dialogs/PublishDialog'
import { RequirementDialog } from './dialogs/RequirementDialog'
import { ShareDialog } from './dialogs/ShareDialog'
import { PanelContent } from './panel/PanelContent'
import { WorkspacePanel } from './panel/WorkspacePanel'
import { ResizeHandle } from './ResizeHandle'
import { isActive, needsRequirementInterview } from './runUtils'
import { useProjectWorkspace } from './useProjectWorkspace'
import { useRequirements } from './useRequirements'
import { useSplitLayout } from './useSplitLayout'
import type { Device, Tab } from './workspaceTypes'
import './workspace.css'

/** A project's workspace: the conversation on the left, preview / code / details on the right. */
export default function ProjectPage() {
  const { id = '' } = useParams()
  const {
    project, setProject, messages, previewUpdate, draftUpdate,
    runs, stepsByRun, selectedRun, steps, loading, notFound,
    hasOlder, loadingOlder, loadOlder,
    editRequest, requestEdit, sendError, send, setFeedback,
    cancel, cancelling, rollback, rollingBack, rollbackRun, rollingBackId, rollbackError,
    acceptRetriedRun,
  } = useProjectWorkspace(id)
  const { layoutMode, setLayoutMode, narrow, chatWidth, dragging, splitRef, resizePointerDown, resizePointerMove, resizePointerUp } = useSplitLayout()
  // On a narrow screen the two sides take turns instead of splitting.
  const showPanel = () => setLayoutMode(narrow ? 'preview-only' : 'split')
  const showChat = () => setLayoutMode(narrow ? 'chat-only' : 'split')
  const openView = (view: Tab) => {
    setTab(view)
    if (layoutMode === 'chat-only') showPanel()
  }
  // The header's reload button: bumped here, followed by the preview panel.
  const [previewReloadRequest, setPreviewReloadRequest] = useState(0)
  // The address the preview shows, for the header's "open in a new tab".
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('preview')
  const [detailsRunId, setDetailsRunId] = useState<string | null>(null)
  useEffect(() => { setDetailsRunId(null); setTab('preview') }, [id])
  const [thinkingPanel, setThinkingPanel] = useState<{ reasoning: string; seconds: number } | null>(null)
  const [shareOpen, setShareOpen] = useState(false)
  const [publishOpen, setPublishOpen] = useState(false)
  const [device, setDevice] = useState<Device>('desktop')

  const showDetailsSplit = useCallback(() => { setLayoutMode('split'); setTab('details') }, [setLayoutMode])
  const { requirements, requirementsBusy, requirementsError, answerRequirement, approveRequirements, skipRequirements } =
    useRequirements(id, project, setProject, showDetailsSplit)

  useEffect(() => {
    if (project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project)) {
      setTab('details')
      setLayoutMode('chat-only')
    }
  }, [project?.lifecycleStage])

  if (notFound) {
    return (
      <AppShell active="chats" surface="plain">
        <div className="ws-missing">
          <EmptyState
            title="Project not found"
            hint="This project does not exist, or it is not shared with you."
            action={<NeuButton href="/dashboard" variant="primary" size="sm">Back to dashboard</NeuButton>}
          />
        </div>
      </AppShell>
    )
  }

  const runActive = !!selectedRun && isActive(selectedRun)
  const canRollback = !!selectedRun && selectedRun.mode === 'BUILD' && selectedRun.rollbackAvailable && !runActive
  const inspectedRun = runs.find(run => run.id === detailsRunId) ?? selectedRun

  return (
    <AppShell active="chats" surface="plain">
      <div className={cx('ws', dragging && 'is-dragging')} ref={splitRef}>
        {shareOpen && <ShareDialog projectId={id} onClose={() => setShareOpen(false)} />}
        {publishOpen && project && <PublishDialog project={project} onClose={() => setPublishOpen(false)} onChange={setProject} />}
        {project?.lifecycleStage === 'REQUIREMENTS' && needsRequirementInterview(project) && requirements && (
          <RequirementDialog
            session={requirements}
            busy={requirementsBusy}
            error={requirementsError}
            onAnswer={answerRequirement}
            onApprove={approveRequirements}
            onSkipAll={skipRequirements}
          />
        )}

        <ChatColumn
          project={project}
          messages={messages}
          runs={runs}
          stepsByRun={stepsByRun}
          loading={loading}
          hasOlder={hasOlder}
          loadingOlder={loadingOlder}
          onLoadOlder={() => void loadOlder()}
          requirements={requirements}
          rollbackError={rollbackError}
          sendError={sendError}
          editRequest={editRequest}
          onSend={send}
          onFeedback={(messageId, feedback) => void setFeedback(messageId, feedback)}
          onEdit={requestEdit}
          onOpenDetails={(runId) => { setDetailsRunId(runId ?? null); openView('details') }}
          onOpenPreview={() => openView('preview')}
          onRetried={acceptRetriedRun}
          onStop={() => void cancel()}
          stopping={cancelling}
          onOpenThinking={(reasoning, seconds) => {
            setThinkingPanel({ reasoning, seconds })
            openView('thinking')
          }}
          onProjectChange={setProject}
          layoutMode={layoutMode}
          chatWidth={chatWidth}
          dragging={dragging}
          onToggleFocus={() => (layoutMode === 'chat-only' ? showPanel() : setLayoutMode('chat-only'))}
        />

        <ResizeHandle
          layoutMode={layoutMode}
          dragging={dragging}
          onRestoreSplit={() => setLayoutMode('split')}
          onPointerDown={resizePointerDown}
          onPointerMove={resizePointerMove}
          onPointerUp={resizePointerUp}
        />

        <WorkspacePanel
          tab={tab}
          onSelectTab={setTab}
          hidden={layoutMode === 'chat-only'}
          expanded={layoutMode === 'preview-only'}
          onToggleExpand={() => (layoutMode === 'preview-only' ? showChat() : setLayoutMode('preview-only'))}
          device={device}
          onDevice={setDevice}
          onReloadPreview={() => setPreviewReloadRequest((n) => n + 1)}
          previewUrl={previewUrl}
          run={{
            active: runActive, saving: selectedRun?.status === 'COMMITTING', cancelling, onCancel: () => void cancel(),
            canRollback, rollingBack, onRollback: () => void rollback(),
          }}
          published={project?.deploymentStatus === 'LIVE'}
          onShare={() => setShareOpen(true)}
          onPublish={() => setPublishOpen(true)}
        >
          <PanelContent
            tab={tab}
            projectId={id}
            project={project}
            activeRunId={runActive ? selectedRun?.id ?? null : null}
            runs={runs}
            selectedRun={tab === 'details' ? inspectedRun : selectedRun}
            steps={tab === 'details' && inspectedRun ? stepsByRun[inspectedRun.id] ?? [] : steps}
            requirements={requirements}
            thinking={thinkingPanel}
            onCloseThinking={() => { setTab('details'); setThinkingPanel(null) }}
            onRestoreRun={rollbackRun}
            rollingBackId={rollingBackId}
            onRetried={acceptRetriedRun}
            preview={{ update: previewUpdate, draftUpdate, reloadRequest: previewReloadRequest, device, onUrlChange: setPreviewUrl }}
          />
        </WorkspacePanel>
      </div>
    </AppShell>
  )
}
