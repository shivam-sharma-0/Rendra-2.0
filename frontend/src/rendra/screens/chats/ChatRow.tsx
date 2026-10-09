import { Link } from 'react-router-dom'
import type { Project } from '../../../api/projects'
import { ICON } from '../../icons'
import { Ico, relTime } from '../../parts'

/** One conversation: its name, opening prompt and last activity. The whole row is the link. */
export default function ChatRow({ chat }: { chat: Project }) {
  return (
    <Link to={`/project/${chat.id}`} className="ch-row">
      <span className="ch-row__icon neu-well" aria-hidden><Ico d={ICON.chats} size={16} sw={1.8} /></span>
      <span className="ch-row__body">
        <b>{chat.name}</b>
        {chat.initialPrompt && <small>{chat.initialPrompt}</small>}
      </span>
      <span className="ch-row__time">{relTime(chat.lastActivityAt)}</span>
      <Ico d={ICON.chevronRight} size={14} sw={2} />
    </Link>
  )
}
