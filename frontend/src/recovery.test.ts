import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatClient } from './chat-client';
import type { ServerEvent, StoredMessage } from './protocol';

class MemoryStorage {
  data = new Map<string,string>();
  getItem(key:string) { return this.data.get(key) ?? null; }
  setItem(key:string,value:string) { this.data.set(key,value); }
  removeItem(key:string) { this.data.delete(key); }
}
class Socket extends EventTarget {
  static OPEN = 1;
  static all: Socket[] = [];
  readyState = 0;
  sent: Record<string,unknown>[] = [];
  constructor(readonly url:string) { super(); Socket.all.push(this); }
  open() { this.readyState = 1; this.dispatchEvent(new Event('open')); }
  send(value:string) { this.sent.push(JSON.parse(value)); }
  close() { this.readyState = 3; this.dispatchEvent(new Event('close')); }
  receive(data:ServerEvent) { this.dispatchEvent(new MessageEvent('message',{data:JSON.stringify(data)})); }
}
let client:ChatClient, socket:Socket, storage:MemoryStorage, local:MemoryStorage;
const url = ()=>'wss://example.test/ws';
const summary = (lastSequence:number,unread=lastSequence) => ({kind:'user' as const,id:'peer',name:'Peer',lastSequence,readSequence:0,unread});
const row = (sequence:number, mine=false, clientMessageId?:string):StoredMessage=>({id:`m${sequence}`,sequence,time:sequence,
  fromUserId:mine?'me':'peer',fromUsername:mine?'Me':'Peer',message:`body ${sequence}`,clientMessageId});
function chat() { return client.getSnapshot().conversations.find(chat=>chat.key==='user:peer')!; }
function auth() {
  client.login('me_user','private-password');
  socket.receive({type:'login',success:true,userId:'me',username:'Me',sessionTicket:'single-use-ticket',connectionId:'lease-1'});
  socket.receive({type:'conversations',syncCursor:10,conversations:[summary(10,0)]});
  client.choose('user','peer');const request=socket.sent.at(-1)!;
  socket.receive({type:'history',requestId:request.requestId as string,kind:'user',id:'peer',messages:[row(10)],nextBefore:'10',hasMore:false});
}
function reconnect() {
  vi.advanceTimersByTime(1000);socket=Socket.all.at(-1)!;socket.open();
  socket.receive({type:'resume',success:true,userId:'me',username:'Me',connectionId:'lease-2'});
}
function page(messages:StoredMessage[],more:boolean,cursor:number) {
  const request=socket.sent.filter(value=>value.type==='history'&&value.after!==undefined).at(-1)!;
  socket.receive({type:'history',kind:'user',id:'peer',requestId:request.requestId as string,messages,nextBefore:null,
    nextAfter:String(messages.at(-1)?.sequence ?? request.after),hasMore:more,syncCursor:cursor});
}
beforeEach(()=>{
  vi.useFakeTimers();Socket.all=[];storage=new MemoryStorage();local=new MemoryStorage();
  vi.stubGlobal('WebSocket',Socket);vi.stubGlobal('sessionStorage',storage);vi.stubGlobal('localStorage',local);
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue({ok:true}));
  client=new ChatClient(url);client.start();socket=Socket.all[0];socket.open();
  socket.receive({type:'resume',success:false});socket.sent=[];
});
afterEach(()=>{client.stop();vi.unstubAllGlobals();vi.useRealTimers();});

describe('credential and incremental recovery',()=>{
  it('exchanges only a short ticket for a Cookie and never caches credentials or passwords',async()=>{
    auth();await Promise.resolve();
    expect(fetch).toHaveBeenCalledWith('https://example.test/api/session',expect.objectContaining({credentials:'include',method:'POST',body:JSON.stringify({ticket:'single-use-ticket'})}));
    const saved=JSON.stringify([...storage.data,...local.data]);
    expect(saved).not.toContain('private-password');expect(saved).not.toContain('single-use-ticket');
    expect(JSON.stringify(client.getSnapshot())).not.toContain('single-use-ticket');
  });
  it('refreshes same account/current conversation/draft and drains more than 50 messages without jumping to a live receipt',()=>{
    auth();client.setDraft('unknown send');client.sendMessage();const request=socket.sent.at(-1)!;client.setDraft('independent draft');
    client.stop();client=new ChatClient(url);client.start();socket=Socket.all.at(-1)!;socket.open();
    expect(socket.sent[0]).toEqual({type:'resume',connectionId:'lease-1'});
    socket.receive({type:'resume',success:true,userId:'me',username:'Me',connectionId:'lease-2'});
    socket.receive({type:'conversations',syncCursor:150,conversations:[summary(150,66)]});
    expect(socket.sent.at(-1)?.after).toBe('10');
    socket.receive({type:'message',fromUserId:'peer',fromUsername:'Peer',id:'m150',sequence:150,time:150,message:'body 150'});
    socket.receive({type:'sent',requestId:request.requestId as string,id:'m80',sequence:80,time:80});
    page(Array.from({length:50},(_,i)=>row(i+11)),true,150);
    expect(socket.sent.at(-1)?.after).toBe('60');
    expect(JSON.parse(storage.getItem('xim.recovery')!).cursors).toEqual([['user:peer',10]]);
    page([...Array.from({length:15},(_,i)=>row(i+61)),row(80,true,request.requestId as string),row(150)],false,150);
    expect(chat().messages).toHaveLength(68);expect(new Set(chat().messages.map(row=>row.id)).size).toBe(68);
    expect(chat().messages.find(row=>row.id==='m80')?.status).toBe('已发送');
    expect(client.getSnapshot()).toMatchObject({current:'user:peer',showChat:true,syncing:false});expect(chat().draft).toBe('independent draft');
    expect(socket.sent.filter(value=>value.type==='message')).toHaveLength(0);
    expect(JSON.parse(storage.getItem('xim.recovery')!).cursors).toEqual([['user:peer',150]]);
  });
  it('restarts an interrupted multi-page pull from its committed frontier and deduplicates retained first-page messages',()=>{
    auth();socket.close();reconnect();socket.receive({type:'conversations',syncCursor:75,conversations:[summary(75)]});
    page(Array.from({length:50},(_,i)=>row(i+11)),true,75);socket.close();reconnect();
    socket.receive({type:'conversations',syncCursor:75,conversations:[summary(75)]});expect(socket.sent.at(-1)?.after).toBe('10');
    page(Array.from({length:50},(_,i)=>row(i+11)),true,75);page(Array.from({length:15},(_,i)=>row(i+61)),false,75);
    expect(chat().messages).toHaveLength(66);expect(client.getSnapshot().syncing).toBe(false);
  });
  it('replays an uncommitted send once after synchronization with the original ID and body',()=>{
    auth();client.setDraft('immutable');client.sendMessage();const request=socket.sent.at(-1)!;
    socket.close();reconnect();socket.receive({type:'conversations',syncCursor:10,conversations:[summary(10)]});
    expect(socket.sent.filter(value=>value.type==='message')).toHaveLength(0);page([],false,10);
    expect(socket.sent.filter(value=>value.type==='message')).toEqual([request]);
    client.retryMessage(request.requestId as string);expect(socket.sent.filter(value=>value.type==='message')).toHaveLength(1);
  });
  it('clears a different account and ignores all late events from the old socket',()=>{
    auth();client.setDraft('private draft');const old=socket;socket.close();reconnect();
    socket.receive({type:'resume',success:true,userId:'other',username:'Other'});
    old.receive({type:'sessionInvalid',code:'replaced',reason:'old'});old.receive({type:'message',fromUserId:'peer',fromUsername:'Peer',message:'old'});old.close();
    expect(client.getSnapshot().session?.userId).toBe('other');expect(client.getSnapshot().conversations).toHaveLength(0);
    expect(JSON.stringify(client.getSnapshot())).not.toContain('private draft');
  });
  it('keeps the workspace on a short break and detects a half-open connection without a close event',()=>{
    auth();client.setDraft('draft');vi.advanceTimersByTime(15000);
    expect(socket.readyState).toBe(3);expect(client.getSnapshot()).toMatchObject({connection:'offline',session:{userId:'me'},current:'user:peer'});
    expect(chat().draft).toBe('draft');reconnect();expect(socket.sent[0]).toEqual({type:'resume',connectionId:'lease-1'});
  });
  it('uses bounded backoff for repeated failed handshakes and cleans every transport timer on stop',()=>{
    auth();socket.close();vi.advanceTimersByTime(1000);const failed=Socket.all.at(-1)!;
    vi.advanceTimersByTime(10000);expect(failed.readyState).toBe(3);const count=Socket.all.length;
    vi.advanceTimersByTime(1999);expect(Socket.all.length).toBe(count);vi.advanceTimersByTime(1);expect(Socket.all.length).toBe(count+1);
    client.stop();expect(vi.getTimerCount()).toBe(0);
  });
  it.each(['replaced','expired','revoked'])('stops automatic restoration after %s, including on refresh',code=>{
    auth();socket.receive({type:'sessionInvalid',code,reason:`reason ${code}`});socket.close();vi.advanceTimersByTime(1000);
    socket=Socket.all.at(-1)!;socket.open();expect(socket.sent).toHaveLength(0);expect(client.getSnapshot().session).toBeNull();
    client.stop();client=new ChatClient(url);client.start();socket=Socket.all.at(-1)!;socket.open();
    expect(socket.sent).toHaveLength(0);expect(client.getSnapshot().loginError).toContain(code);
    expect(client.login('me_user','private-password')).toBe(true);
  });
  it('revokes on logout immediately, even without a wire reply, and retries server-side deletion after an offline failure',async()=>{
    auth();vi.mocked(fetch).mockRejectedValueOnce(new Error('offline'));client.logout();await vi.advanceTimersByTimeAsync(0);
    expect(client.getSnapshot().session).toBeNull();expect(storage.getItem('xim.recovery')).toBeNull();expect(local.getItem('xim.logoutPending')).toBe('true');
    await vi.advanceTimersByTimeAsync(1000);socket=Socket.all.at(-1)!;socket.open();await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenLastCalledWith('https://example.test/api/session',expect.objectContaining({method:'DELETE',credentials:'include'}));
    expect(local.getItem('xim.logoutPending')).toBeNull();expect(socket.sent).toHaveLength(0);
  });
  it('does not restore a group removed while offline',()=>{
    auth();socket.receive({type:'joinGroup',success:true,groupId:'g'});client.setDraft('group draft');socket.close();reconnect();
    socket.receive({type:'conversations',syncCursor:10,conversations:[summary(10)]});page([],false,10);
    expect(client.getSnapshot().conversations.some(chat=>chat.key==='group:g')).toBe(false);expect(client.getSnapshot().current).toBeNull();
  });
  it('reconnects on a missing sync response while preserving its frontier',()=>{
    auth();socket.close();reconnect();socket.receive({type:'conversations',syncCursor:75,conversations:[summary(75)]});
    vi.advanceTimersByTime(8000);expect(socket.readyState).toBe(3);
    expect(JSON.parse(storage.getItem('xim.recovery')!).cursors).toEqual([['user:peer',10]]);
  });
  it('times out a missing recovery snapshot instead of remaining in syncing indefinitely',()=>{
    auth();socket.close();reconnect();vi.advanceTimersByTime(8000);
    expect(socket.readyState).toBe(3);expect(client.getSnapshot().connection).toBe('offline');
  });
  it('retains explicit failures across refresh for manual retry rather than replaying them automatically',()=>{
    auth();client.setDraft('rejected');client.sendMessage();const request=socket.sent.at(-1)!;
    socket.receive({type:'error',requestId:request.requestId as string,reason:'permission denied'});
    client.stop();client=new ChatClient(url);client.start();socket=Socket.all.at(-1)!;socket.open();
    socket.receive({type:'resume',success:true,userId:'me',username:'Me'});
    socket.receive({type:'conversations',syncCursor:10,conversations:[summary(10)]});page([],false,10);
    expect(socket.sent.filter(value=>value.type==='message')).toHaveLength(0);
    client.retryMessage(request.requestId as string);expect(socket.sent.filter(value=>value.type==='message')).toEqual([request]);
  });
  it('waits for pending Cookie deletion before permitting a fresh login to avoid clearing the new credential',async()=>{
    auth();await Promise.resolve();let complete!:(value:Response)=>void;
    vi.mocked(fetch).mockReturnValueOnce(new Promise(resolve=>{complete=resolve;}) as Promise<Response>);
    client.logout();vi.advanceTimersByTime(1000);socket=Socket.all.at(-1)!;socket.open();
    expect(client.login('me_user','private-password')).toBe(false);
    complete({ok:true} as Response);await Promise.resolve();await Promise.resolve();await Promise.resolve();
    expect(client.login('me_user','private-password')).toBe(true);
  });
  it('reports failed credential exchange without persisting the ticket',async()=>{
    vi.mocked(fetch).mockResolvedValueOnce({ok:false} as Response);auth();
    await Promise.resolve();await Promise.resolve();
    expect(client.getSnapshot().toast).toContain('凭证未保存');expect(storage.getItem('xim.restoreBlocked')).toBe('true');
    expect(JSON.stringify([...storage.data])).not.toContain('single-use-ticket');
  });

});
