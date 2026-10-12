export interface User { userId: string; username: string }

export type ClientEvent =
  | { type: 'login'; username: string; password: string }
  | { type: 'logout' | 'users' | 'heartbeat' }
  | { type: 'message'; toUserId: string; message: string; requestId: string }
  | { type: 'groupMessage'; groupId: string; message: string; requestId: string }
  | { type: 'createGroup'; userIds: string[] }
  | { type: 'joinGroup' | 'members' | 'quitGroup'; groupId: string };

export type ServerEvent =
  | { type: 'login'; success: true; userId: string; username: string }
  | { type: 'login'; success: false; reason?: string }
  | { type: 'logout'; success: boolean }
  | { type: 'users'; users: User[] }
  | { type: 'message'; fromUserId: string; fromUsername: string; message: string }
  | { type: 'groupMessage'; fromGroupId: string; fromUserId: string; fromUser: string; message: string }
  | { type: 'createGroup' | 'joinGroup' | 'quitGroup'; success: boolean; groupId: string; reason?: string }
  | { type: 'members'; groupId: string; sessions: (User | null)[] }
  | { type: 'sent'; requestId?: string }
  | { type: 'error'; requestId?: string; reason: string }
  | { type: 'heartbeat' };

export interface Message {
  id: string;
  time: number;
  mine: boolean;
  name: string;
  text: string;
  status?: string;
}

export interface Conversation {
  key: string;
  kind: 'user' | 'group';
  id: string;
  name: string;
  messages: Message[];
  unread: number;
  draft: string;
}

export interface ChatState {
  connection: 'connecting' | 'online' | 'offline';
  session: User | null;
  users: User[];
  conversations: Conversation[];
  current: string | null;
  tab: 'conversations' | 'users';
  showChat: boolean;
  loginPending: boolean;
  loginError: string;
  toast: string;
  members: User[] | null;
}
