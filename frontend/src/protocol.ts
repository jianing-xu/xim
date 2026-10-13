import type { Int64 } from './integers';
export interface User { userId: string; username: string }
export interface Contact extends User { displayName: string; online: boolean }
export interface ContactDirectory {
  query: string; users: Contact[]; loading: boolean; loaded: boolean;
  error: string; nextAfter: string | null; hasMore: boolean;
}

export type ClientEvent =
  | { type: 'login'; username: string; password: string }
  | { type: 'register'; username: string; password: string; displayName?: string; requestId: string }
  | { type: 'contacts'; query: string; after?: string; limit: number; requestId: string }
  | { type: 'changePassword'; currentPassword: string; newPassword: string; requestId: string }
  | { type: 'resume'; connectionId?: string }
  | { type: 'logout' | 'users' | 'heartbeat' | 'conversations' | 'revokeSessions' }
  | { type: 'history'; kind: 'user' | 'group'; id: string; before?: string; after?: string; limit: number; requestId: string }
  | { type: 'read'; kind: 'user' | 'group'; id: string; sequence: string; requestId: string }
  | { type: 'message'; toUserId: string; message: string; requestId: string }
  | { type: 'groupMessage'; groupId: string; message: string; requestId: string }
  | { type: 'createGroup'; userIds: string[] }
  | { type: 'joinGroup' | 'members' | 'quitGroup'; groupId: string };

export interface StoredMessage { sequence?: Int64; clientMessageId?: string; id: string; time: number; fromUserId: string; fromUsername: string; message: string }

export type ServerEvent =
  | { type: 'register' | 'changePassword'; success: boolean; reason?: string; requestId?: string }
  | { type: 'login' | 'resume'; success: true; userId: string; username: string; sessionTicket?: string; connectionId?: string; expiresAt?: number }
  | { type: 'login' | 'resume'; success: false; reason?: string }
  | { type: 'sessionInvalid'; code: string; reason: string }
  | { type: 'logout'; success: boolean }
  | { type: 'users'; users: User[] }
  | { type: 'contacts'; requestId: string; users: Contact[]; nextAfter: string | null; hasMore: boolean }
  | { type: 'message'; fromUserId: string; fromUsername: string; message: string; requestId?: string; id?: string; time?: number; sequence?: Int64 }
  | { type: 'groupMessage'; fromGroupId: string; fromUserId: string; fromUser: string; message: string; requestId?: string; id?: string; time?: number; sequence?: Int64 }
  | { type: 'createGroup' | 'joinGroup' | 'quitGroup'; success: boolean; groupId: string; reason?: string }
  | { type: 'members'; groupId: string; sessions: (User | null)[] }
  | { type: 'sent'; requestId?: string; id?: string; time?: number; sequence?: Int64 }
  | { type: 'conversations'; conversations: ConversationSummary[]; syncCursor?: Int64; snapshotId?: string; part?: number; hasMore?: boolean }
  | { type: 'read'; requestId: string; conversation: ConversationSummary; syncCursor: Int64 }
  | { type: 'history'; requestId: string; kind: 'user' | 'group'; id: string; messages: StoredMessage[]; nextBefore: string | null; hasMore: boolean; nextAfter?: string; syncCursor?: Int64 }
  | { type: 'error'; requestId?: string; reason: string }
  | { type: 'heartbeat' };

export interface ConversationSummary {
  kind: 'user' | 'group'; id: string; name: string;
  lastMessage?: StoredMessage; lastSequence?: Int64; lastTime?: number;
  readSequence?: Int64; unread?: Int64;
}

export interface Message {
  fromUserId?: string;
  sequence?: Int64;
  id: string;
  time: number;
  mine: boolean;
  name: string;
  text: string;
  status?: string;
  clientMessageId?: string;
  retryable?: boolean;
}

export interface Conversation extends ConversationSummary {
  key: string;
  kind: 'user' | 'group';
  id: string;
  name: string;
  messages: Message[];
  unread: Int64;
  draft: string;
  summaryCursor?: Int64;
  historyLoaded?: boolean;
  historyLoading?: boolean;
  hasMore?: boolean;
  nextBefore?: string | null;
}

export interface ChatState {
  restoring: boolean;
  syncing: boolean;
  syncCursor: Int64;
  connection: 'connecting' | 'online' | 'offline';
  session: User | null;
  users: User[];
  directory: ContactDirectory;
  conversations: Conversation[];
  current: string | null;
  tab: 'conversations' | 'users';
  showChat: boolean;
  loginPending: boolean;
  loginError: string;
  accountPending: boolean;
  accountError: string;
  accountNotice: string;
  toast: string;
  members: User[] | null;
}
