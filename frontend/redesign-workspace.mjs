import fs from 'node:fs'
const path = 'src/pages/Project.tsx'
let s = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n')
const replaceBetween = (start, end, value) => {
  const a = s.indexOf(start), b = s.indexOf(end, a)
  if (a < 0 || b < 0) throw new Error(`Missing marker: ${start}`)
  s = s.slice(0, a) + value + s.slice(b)
}
s = s.replace("<div className={'pw w-full h-full flex flex-row bg-[var(--bg)] text-[var(--ink)] font-[Inter,Geist,system-ui,-apple-system,sans-serif] text-base animate-[pw-fade_.26s_ease_both] motion-reduce:animate-none' + (dragging ? ' dragging' : '')} ref={splitRef}>", `<div className={'pw pw-workspace' + (dragging ? ' dragging' : '')}>
        <header className="pw-workspace-head">
          <Link to="/projects" className="pw-back" aria-label="Back to projects" title="Back to projects"><Ico d="M15 18l-6-6 6-6" size={18} /></Link>
          <div className="pw-project-identity"><span className="pw-eyebrow">WORKSPACE</span><h1>{project?.name ?? 'Loading project…'}</h1></div>
          <span className="pw-project-status">{runActive ? 'Working' : project?.deploymentStatus === 'LIVE' ? 'Published' : 'Draft'}</span>
          <div className="pw-workspace-actions">
            <button type="button" className="pw-panelbtn" onClick={toggleTheme} aria-label={resolvedTheme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'} title="Switch theme"><Ico d={resolvedTheme === 'dark' ? 'M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 0-8 0 4 4 0 0 0 8 0' : 'M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13Z'} size={17} /></button>
            <button type="button" className="pw-share-btn" onClick={() => setShareOpen(true)}>Share</button>
            <button type="button" className="pw-publish" onClick={() => setPublishOpen(true)} disabled={!project}>{project?.deploymentStatus === 'LIVE' ? 'Published' : 'Publish'}<Ico d="M7 17 17 7M7 7h10v10" size={14} /></button>
          </div>
        </header>
        <nav className="pw-mobile-switch" aria-label="Workspace view">
          <button type="button" aria-pressed={mobilePane === 'chat'} onClick={() => setMobilePane('chat')}>Conversation</button>
          <button type="button" aria-pressed={mobilePane === 'panel'} onClick={() => setMobilePane('panel')}>Workspace</button>
        </nav>
        <div className="pw-workspace-body" ref={splitRef}>`)
s = s.replace("<section className={'pw-chat'", "<section inert={!chatVisible} aria-label=\"Conversation\" className={'pw-chat'")
s = s.replace("width: layoutMode === 'split' ? chatWidth : undefined,", "width: narrow ? '100%' : layoutMode === 'split' ? `clamp(300px, ${chatWidth}px, 48%)` : undefined,")
replaceBetween('            <button type="button" className="pw-project">', '            <button\n              className="pw-panelbtn"', `            <div className="pw-conversation-title"><Ico d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9H13a8.5 8.5 0 0 1 8 8v.5Z" size={16} /><b>Conversation</b></div>
            <span className="pw-spacer" />
`)
s = s.replace('<div className="pw-chat-scroll">', `<div className="pw-chat-scroll" ref={chatScrollRef} onScroll={(event) => {
            const el = event.currentTarget
            followLatest.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
            setShowJump(!followLatest.current)
          }}>`)
s = s.replace("onOpenDetails={() => setTab('details')} onOpenPreview={() => setTab('preview')}", "onOpenDetails={() => openPanel('details')} onOpenPreview={() => openPanel('preview')}")
s = s.replace("setTab('thinking')\n                            if (layoutMode === 'chat-only') setLayoutMode('split')", "openPanel('thinking')")
s = s.replace("onPreview={() => setTab('preview')}", "onPreview={() => openPanel('preview')}")
s = s.replace('          <Composer defaultMode=', `          {showJump && <button type="button" className="pw-jump" onClick={() => {
            followLatest.current = true
            setShowJump(false)
            if (chatScrollRef.current) chatScrollRef.current.scrollTop = chatScrollRef.current.scrollHeight
          }}>Jump to latest ↓</button>}
          <Composer key={id} defaultMode=`)
s = s.replace('          aria-label="Resize chat panel"', `          aria-label="Resize chat panel"
          tabIndex={narrow ? -1 : 0}
          aria-valuemin={MIN_CHAT_WIDTH}
          aria-valuemax={Math.max(MIN_CHAT_WIDTH, (splitRef.current?.clientWidth ?? 1000) - MIN_PANEL_WIDTH)}
          aria-valuenow={chatWidth}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); setLayoutMode('split') }
            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
              e.preventDefault()
              setLayoutMode('split')
              setChatWidth((width) => clamp(width + (e.key === 'ArrowLeft' ? -24 : 24), MIN_CHAT_WIDTH, Math.max(MIN_CHAT_WIDTH, (splitRef.current?.clientWidth ?? 1000) - MIN_PANEL_WIDTH)))
            }
          }}
          onPointerCancel={() => setDragging(false)}`)
s = s.replace('Drag to resize · click to focus', 'Drag or use arrow keys to resize')
replaceBetween('        <section ref={panelRef}', '          {/* More dropdown', `        <section ref={panelRef} inert={!panelVisible} aria-label="Project workspace" className={'pw-panel' + (tab === 'code' ? ' code' : '') + (!panelVisible ? ' pw-panel--hidden' : '')}>
          <div className="pw-tabs">
            <div className="pw-tab-grp" role="tablist" aria-label="Project panels">
              {WS_MAIN_TABS.map(t => (
                <button key={t.id} type="button" role="tab" aria-selected={tab === t.id || (tab === 'thinking' && t.id === 'details')}
                  className={'pw-tab' + (tab === t.id || (tab === 'thinking' && t.id === 'details') ? ' active' : '')}
                  onClick={() => openPanel(t.id)}>
                  <Ico d={t.icon} size={15} /><span className="pw-tab-label">{t.label}</span>
                </button>
              ))}
            </div>
            <button ref={moreRef} type="button" className={'pw-tab pw-tab--more' + (WS_MORE_TABS.some(t => t.id === tab) ? ' active' : '')}
              aria-label="More project tools" aria-expanded={moreOpen} aria-haspopup="true"
              onClick={() => {
                const rect = moreRef.current?.getBoundingClientRect()
                if (rect) setMorePos({ top: rect.bottom + 6, left: Math.max(8, Math.min(rect.left, window.innerWidth - 206)) })
                setMoreOpen(value => !value)
              }}><Ico d="M5 12h.01M12 12h.01M19 12h.01" size={18} /><span>More</span></button>
            <span className="pw-spacer" />
            <button type="button" className="pw-panelbtn pw-expand" onClick={() => setLayoutMode(layoutMode === 'preview-only' ? 'split' : 'preview-only')}
              aria-label={layoutMode === 'preview-only' ? 'Restore split view' : 'Expand workspace'} title={layoutMode === 'preview-only' ? 'Restore split view' : 'Expand workspace'}>
              <Ico d={layoutMode === 'preview-only' ? 'M15 3v6h6M9 21v-6H3M15 9l6-6M9 15l-6 6' : 'M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7'} size={16} />
            </button>
          </div>
          {tab !== 'preview' && tab !== 'code' && <div className="pw-activity-toolbar"><span>{PANEL_TITLES[tab]}</span><span className="pw-spacer" />
            {runActive && <button type="button" className="pw-hbtn" onClick={() => void cancel()} disabled={cancelling}>{cancelling ? 'Stopping…' : 'Stop build'}</button>}
            {canRollback && <button type="button" className="pw-hbtn" onClick={() => void rollback()} disabled={rollingBack}>{rollingBack ? 'Restoring…' : 'Restore last build'}</button>}
          </div>}

`)
s = s.replace('(panelCompact ? [...WS_MAIN_TABS, ...WS_MORE_TABS] : WS_MORE_TABS)\n                  .filter(t => !(panelCompact && (t.id === tab || (tab === \'thinking\' && t.id === \'details\'))))', 'WS_MORE_TABS')
s = s.replace("onClick={() => { setTab(t.id); setMoreOpen(false) }}", "onClick={() => openPanel(t.id)}")
s = s.replace('className="pw-more-pop" style=', 'className="pw-more-pop pw-tool-menu" style=')
replaceBetween('          <div key={tab} className="pw-panel-content">', '\n        </section>\n      </div>', `          <div className="pw-panel-content" key={id}>
            <div className="pw-panel-page" hidden={tab !== 'preview'}>
              <PreviewPanel project={project} update={previewUpdate} draftUpdate={draftUpdate} reloadRequest={previewReloadRequest} device={device} onDevice={setDevice} onReload={() => setPreviewReloadRequest(n => n + 1)} />
            </div>
            <div className="pw-panel-page" hidden={tab !== 'code'}>
              <CodePanel projectId={id} activeRunId={runActive ? selectedRun?.id ?? null : null} />
            </div>
            {tab === 'thinking' && thinkingPanel ? <ThinkingPanel reasoning={thinkingPanel.reasoning} seconds={thinkingPanel.seconds} onBack={() => { setTab('details'); setThinkingPanel(null) }} />
              : tab === 'files' ? <FilesPanel projectId={id} activeRunId={runActive ? selectedRun?.id ?? null : null} />
              : tab === 'history' ? <HistoryPanel runs={runs} onRestore={rollbackRun} rollingBackId={rollingBackId} />
              : tab === 'details' ? <DetailsPanel run={selectedRun} steps={steps} stage={project?.lifecycleStage} requirements={requirements} />
              : tab === 'analytics' ? <AnalyticsPanel projectId={id} active />
              : tab === 'security' ? <SecurityPanel projectId={id} active />
              : tab === 'database' ? <DatabasePanel projectId={id} /> : null}
          </div>`)
s = s.replace('\n        </section>\n      </div>\n    </AppShell>', '\n        </section>\n        </div>\n      </div>\n    </AppShell>')
fs.writeFileSync(path, s)
