import { Link } from 'react-router-dom'
import type { Project } from '../../../api/projects'
import { AppIcon } from '../../../components/icons'
import { relTime } from '../../parts'

/** One conversation: its name, opening prompt and last activity. The whole row is the link. */
export default function ChatRow({ chat, onOpen }: { chat: Project; onOpen?: () => void }) {
  return (
    <Link to={`/project/${chat.id}`} className="ch-row" onClick={onOpen}>
      <span className="ch-row__icon neu-well" aria-hidden><AppIcon name="chats" /></span>
      <span className="ch-row__body">
        <b>{chat.name}</b>
        {chat.initialPrompt && <small>{chat.initialPrompt}</small>}
      </span>
      <span className="ch-row__time">{relTime(chat.lastActivityAt)}</span>
      <AppIcon name="caretRight" />
    </Link>
  )
}
