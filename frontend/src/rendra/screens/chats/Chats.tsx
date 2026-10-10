import { AppIcon } from '../../../components/icons'
import { NeuButton, NeuPager, NeuPanel, NeuSearchField } from '../../../components/neu'
import { EmptyState, ErrorState, Spinner } from '../../../components/ui/States'
import AppShell from '../../AppShell'
import PageHeader from '../../ui/PageHeader'
import ChatRow from './ChatRow'
import { useChatList } from './useChatList'
import './chats.css'

const PAGE_SIZE = 10

/** The workspace's AI conversations: search, paging and "New chat". */
export default function Chats() {
  const list = useChatList(PAGE_SIZE)

  return (
    <AppShell active="chats" surface="plain">
      <div className="page">
        <div className="page__inner">
          <PageHeader
            title="Chats"
            description="Your AI conversations, all in one place."
            action={
              <NeuButton variant="primary" size="sm" pill disabled={list.starting} onClick={() => void list.newChat()}>
                {list.starting ? <Spinner size={16} /> : <AppIcon name="plus" />}New chat
              </NeuButton>
            }
          />
          <NeuSearchField className="ch-search" value={list.search} onChange={list.updateSearch} label="Search chats" placeholder="Search chats…" />

          {list.isLoading ? (
            <div className="page__state"><Spinner /></div>
          ) : list.isError ? (
            <ErrorState message="We couldn't load your chats." onRetry={list.refetch} />
          ) : list.total === 0 ? (
            <EmptyState
              title={list.search ? `No chats match "${list.search}"` : 'No chats yet'}
              hint={list.search ? 'Try a different search term.' : 'Start a new chat to get going.'}
            />
          ) : (
            <>
              <NeuPanel size="sm" className="ch-list" role="list">
                {list.shown.map((p) => <div role="listitem" key={p.id}><ChatRow chat={p} /></div>)}
              </NeuPanel>
              <NeuPager page={list.page} pageCount={list.pageCount} onChange={list.setPage} />
            </>
          )}
        </div>
      </div>
    </AppShell>
  )
}
