import { AppIcon } from '../../components/icons'
import { NeuButton, NeuDialog, NeuPager, NeuSearchField } from '../../components/neu'
import { ErrorState, Spinner } from '../../components/ui/States'
import ChatRow from '../screens/chats/ChatRow'
import { useChatList } from '../screens/chats/useChatList'
import '../screens/chats/chats.css'

const PAGE_SIZE = 6

/** The sidebar's "Chats": the workspace's chats with search and paging, and "New chat". Mounted only while open. */
export default function ChatsDialog({ onClose }: { onClose: () => void }) {
  const list = useChatList(PAGE_SIZE)

  return (
    <NeuDialog title="Chats" description="Your AI conversations in this workspace." onClose={onClose} className="ch-dialog">
      <div className="ch-dialog__bar">
        <NeuSearchField value={list.search} onChange={list.updateSearch} label="Search chats" placeholder="Search chats…" autoFocus />
        <NeuButton variant="accent" disabled={list.starting} onClick={() => void list.newChat(onClose)}>
          {list.starting ? <Spinner size={16} /> : <AppIcon name="plus" />}New chat
        </NeuButton>
      </div>
      {list.isLoading ? (
        <div className="ch-dialog__state"><Spinner /></div>
      ) : list.isError ? (
        <ErrorState message="We couldn't load your chats." onRetry={list.refetch} />
      ) : list.total === 0 ? (
        <p className="ch-dialog__state">{list.search ? `No chats match “${list.search.trim()}”.` : 'No chats yet — start a new one.'}</p>
      ) : (
        <>
          <div className="ch-list ch-dialog__list" role="list">
            {list.shown.map((p) => <div role="listitem" key={p.id}><ChatRow chat={p} onOpen={onClose} /></div>)}
          </div>
          <NeuPager page={list.page} pageCount={list.pageCount} onChange={list.setPage} />
        </>
      )}
    </NeuDialog>
  )
}
