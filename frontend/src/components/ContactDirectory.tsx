import type { ChatClient } from '../chat-client';
import type { ChatState } from '../protocol';

export function ContactSearch({ client, state, id = 'contact-search' }: { client: ChatClient; state: ChatState; id?: string }) {
  return <div className="search"><span aria-hidden="true">⌕</span><input id={id} type="search" maxLength={64}
    placeholder="搜索账号或显示名" aria-label="搜索账号或显示名" value={state.directory.query}
    onChange={event => client.searchContacts(event.target.value)} /></div>;
}

export function ContactStatus({ client, state }: { client: ChatClient; state: ChatState }) {
  const directory = state.directory;
  return <div className="directory-status" aria-live="polite">
    {directory.loading && <p role="status">正在查找联系人…</p>}
    {directory.error && <><p role="alert">{directory.error}</p>
      <button className="text-button" type="button" disabled={directory.loading} onClick={() => client.retryContacts()}>重试联系人查询</button></>}
    {!directory.loading && !directory.error && directory.loaded && !directory.users.length && <p>
      {directory.query.trim() ? '没有找到匹配的账号或显示名' : '还没有其他联系人'}
    </p>}
    {!directory.error && directory.hasMore && <button className="text-button" type="button" disabled={directory.loading}
      onClick={() => client.loadMoreContacts()}>加载更多联系人</button>}
    {!directory.loading && !directory.error && directory.loaded && <button className="text-button" type="button"
      onClick={() => client.searchContacts(directory.query)}>刷新联系人</button>}
  </div>;
}
