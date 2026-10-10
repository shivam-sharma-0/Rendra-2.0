import type { ActivityKind, AgentActivity, AgentStep } from '../../api/projects'

/** One row of a run's timeline: thinking (expandable reasoning), an activity, or a step from an older server. */
export type TimelineEntry =
  | { type: 'thinking'; key: string; step: AgentStep; since: string }
  | { type: 'activity'; key: string; activity: AgentActivity }
  | { type: 'legacy'; key: string; step: AgentStep }

/** Activities that change the project's files (the build card shows once one of them happens). */
export const FILE_CHANGE_KINDS: ReadonlySet<ActivityKind> = new Set(['CREATE', 'EDIT', 'DELETE', 'MOVE'])
/** Consecutive rows of these kinds with the same title merge into one ("Created" + all their files). */
const MERGEABLE_KINDS: ReadonlySet<ActivityKind> = new Set(['READ', 'CREATE', 'EDIT', 'DELETE'])

/**
 * The rows to show for a run's steps: a check that is still running disappears once its result
 * arrives, consecutive file activities of one kind merge, and steps from a server that does not send
 * activities fall back to the older step rendering.
 */
export function timelineEntries(steps: AgentStep[], startedAt: string): TimelineEntry[] {
  const entries: TimelineEntry[] = []
  steps.forEach((step, index) => {
    const activity = step.activity
    if (!activity) {
      entries.push({ type: 'legacy', key: `s${step.seq}`, step })
      return
    }
    if (activity.kind === 'THINKING') {
      entries.push({ type: 'thinking', key: `s${step.seq}`, step, since: steps[index - 1]?.createdAt ?? startedAt })
      return
    }
    if (activity.kind === 'CHECK' && step.type === 'TOOL_CALL'
      && steps.slice(index + 1).some((later) => later.type === 'TOOL_RESULT' && later.content === step.content)) {
      return
    }
    const last = entries[entries.length - 1]
    if (last?.type === 'activity' && MERGEABLE_KINDS.has(activity.kind)
      && last.activity.kind === activity.kind && last.activity.title === activity.title) {
      last.activity = { ...last.activity, paths: [...new Set([...last.activity.paths, ...activity.paths])] }
      return
    }
    entries.push({ type: 'activity', key: `s${step.seq}`, activity })
  })
  return entries
}
