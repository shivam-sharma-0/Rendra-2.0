import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { cx } from '../../lib/cx'
import AppShell from '../../rendra/AppShell'
import { ChatColumn } from './chat/ChatColumn'
import { PublishDialog } from './dialogs/PublishDialog'
import { RequirementDialog } from './dialogs/RequirementDialog'
import { ShareDialog } from './dialogs/ShareDialog'
import { PanelContent } from './panel/PanelContent'
import { PanelHeader } from './panel/PanelHeader'
import { PanelRail } from './panel/PanelRail'
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
  } = useProjectWorkspace(id)
  const { layoutMode, setLayoutMode, chatWidth, dragging, splitRef, resizePointerDown, resizePointerMove, resizePointerUp } = useSplitLayout()
  // The header's reload button: bumped here, followed by the preview panel.
  const [previewReloadRequest, setPreviewReloadRequest] = useState(0)
  const [tab, setTab] = useState<Tab>('code')
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
      <AppShell active="chats">
        <div className="pw-missing">
          <div>
            <p>This project does not exist or is not available to you.</p>
            <Link to="/dashboard">← Back to dashboard</Link>
          </div>
        </div>
      </AppShell>
    )
  }

  const runActive = !!selectedRun && isActive(selectedRun)
  const canRollback = !!selectedRun && selectedRun.mode === 'BUILD' && selectedRun.rollbackAvailable && !runActive

  return (
    <AppShell active="chats" surface="plain">
      <div className={cx('pw ws', dragging && 'is-dragging')} ref={splitRef}>
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
          onOpenDetails={() => setTab('details')}
          onOpenPreview={() => setTab('preview')}
          onOpenThinking={(reasoning, seconds) => {
            setThinkingPanel({ reasoning, seconds })
            setTab('thinking')
            if (layoutMode === 'chat-only') setLayoutMode('split')
          }}
          layoutMode={layoutMode}
          chatWidth={chatWidth}
          dragging={dragging}
          onToggleFocus={() => setLayoutMode(layoutMode === 'chat-only' ? 'split' : 'chat-only')}
        />

        <ResizeHandle
          layoutMode={layoutMode}
          dragging={dragging}
          onRestoreSplit={() => setLayoutMode('split')}
          onPointerDown={resizePointerDown}
          onPointerMove={resizePointerMove}
          onPointerUp={resizePointerUp}
        />

        <section className={cx('ws-panel', layoutMode === 'chat-only' && 'ws-panel--hidden')}>
          <PanelRail
            tab={tab}
            onSelect={setTab}
            expanded={layoutMode === 'preview-only'}
            onToggleExpand={() => setLayoutMode(layoutMode === 'preview-only' ? 'split' : 'preview-only')}
          />
          <div className="ws-panel__main">
            <PanelHeader
              tab={tab}
              device={device}
              onDevice={setDevice}
              onReloadPreview={() => setPreviewReloadRequest((n) => n + 1)}
              run={{
                active: runActive, cancelling, onCancel: () => void cancel(),
                canRollback, rollingBack, onRollback: () => void rollback(),
              }}
              published={project?.deploymentStatus === 'LIVE'}
              onShare={() => setShareOpen(true)}
              onPublish={() => setPublishOpen(true)}
            />
            <div className="ws-screen">
              <PanelContent
                tab={tab}
                projectId={id}
                project={project}
                activeRunId={runActive ? selectedRun?.id ?? null : null}
                runs={runs}
                selectedRun={selectedRun}
                steps={steps}
                requirements={requirements}
                thinking={thinkingPanel}
                onCloseThinking={() => { setTab('details'); setThinkingPanel(null) }}
                onRestoreRun={rollbackRun}
                rollingBackId={rollingBackId}
                preview={{ update: previewUpdate, draftUpdate, reloadRequest: previewReloadRequest }}
              />
            </div>
          </div>
        </section>
      </div>
    </AppShell>
  )
}
