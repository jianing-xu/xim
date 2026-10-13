import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { xim } from './generated/xim';
import { encodeClientEvent, encodeServerEvent, decodeClientEvent, decodeServerEvent, decodeWire, MAX_FRAME_BYTES } from './protocol-wire';
import { compareInt, maxInt, integer } from './integers';
import type { ClientEvent, ServerEvent } from './protocol';
const Envelope = xim.v2.Envelope;
describe('typed Protobuf wire', () => {
  it('round trips every request/event, Unicode, all optional IDs and precise nested int64 values', () => {
    const requests: ClientEvent[] = [
      { type:'login',username:'用户',password:'quote" 密码' }, {type:'register',username:'new_user',password:'test-only',displayName:'小林',requestId:'reg'},
      {type:'changePassword',currentPassword:'old',newPassword:'new',requestId:'change'}, {type:'resume',connectionId:'lease'},
      ...(['logout','users','heartbeat','conversations','revokeSessions'] as const).map(type=>({type})),
      {type:'contacts',query:'中文_%',after:'user-cursor',limit:20,requestId:'directory'},
      {type:'history',kind:'user',id:'peer',after:'9007199254740993',limit:50,requestId:'page'},
      {type:'history',kind:'group',id:'group',before:'9223372036854775807',limit:50,requestId:'before'},
      {type:'read',kind:'user',id:'peer',sequence:'9223372036854775807',requestId:'read'},
      {type:'message',toUserId:'peer',message:'中文 🌏\n<script>',requestId:'client-id'}, {type:'groupMessage',groupId:'group',message:'群 🐱',requestId:'client-id'},
      {type:'createGroup',userIds:[]}, ...(['joinGroup','members','quitGroup'] as const).map(type=>({type,groupId:'group'})),
    ];
    for (const request of requests) expect(decodeClientEvent(encodeClientEvent(request))).toEqual(request);
    const summary = {kind:'user' as const,id:'peer',name:'Peer',lastSequence:'9223372036854775807',readSequence:'9007199254740993',unread:2};
    const events: ServerEvent[] = [
      {type:'login',success:true,userId:'me',username:'Me',sessionTicket:'single-use',connectionId:'lease',expiresAt:1700000000000}, {type:'resume',success:false,reason:'expired'},
      {type:'register',success:false,reason:'duplicate',requestId:'reg'}, {type:'changePassword',success:true,requestId:'change'},
      {type:'sessionInvalid',code:'replaced',reason:'another window'}, {type:'logout',success:true}, {type:'users',users:[]}, {type:'contacts',requestId:'directory',users:[],hasMore:false,nextAfter:null},
      {type:'message',fromUserId:'peer',fromUsername:'用户',message:'中文 📨',id:'server',sequence:'9223372036854775807',time:1700000000000,requestId:'client-id'},
      {type:'groupMessage',fromGroupId:'g',fromUserId:'peer',fromUser:'用户',message:'群 🐱',id:'group-server',sequence:'9007199254740993',time:1700000000000},
      {type:'createGroup',success:true,groupId:'g'}, {type:'joinGroup',success:false,groupId:'g',reason:'missing'}, {type:'quitGroup',success:true,groupId:'g'},
      {type:'members',groupId:'g',sessions:[]}, {type:'sent',id:'server',sequence:'9223372036854775807',time:1700000000000,requestId:'client-id'},
      {type:'conversations',conversations:[summary],syncCursor:'9223372036854775807'}, {type:'read',requestId:'read',conversation:summary,syncCursor:'9223372036854775807'},
      {type:'history',requestId:'page',kind:'user',id:'peer',messages:[{id:'server',sequence:'9223372036854775807',fromUserId:'peer',fromUsername:'用户',message:'中文',time:1700000000000,clientMessageId:'client-id'}],hasMore:false,nextBefore:'9223372036854775807',nextAfter:'9223372036854775807',syncCursor:'9223372036854775807'},
      {type:'error',requestId:'x',reason:'safe'}, {type:'heartbeat'},
    ];
    for (const event of events) {
      const actual = decodeServerEvent(encodeServerEvent(event));
      expect(actual.type).toBe(event.type); expect(actual).toMatchObject(Object.fromEntries(Object.entries(event).filter(([,v])=>v!==null)));
    }
  });
  it('reads shared Java/TypeScript golden vectors and ignores unknown future fields', () => {
    const fixtures=JSON.parse(readFileSync(new URL('../../protocol/golden-vectors.json',import.meta.url),'utf8'));
    for(const fixture of fixtures) {
      const bytes=Uint8Array.from(Buffer.from(fixture.base64,'base64'));
      const result=decodeWire(bytes,fixture.request); expect(result.type).toBe(fixture.event.type);
      if(fixture.event.syncCursor) expect(result.syncCursor).toBe(fixture.event.syncCursor);
      if(fixture.event.after) expect(result.after).toBe(fixture.event.after);
      if(fixture.event.sequence) expect(result.sequence).toBe(fixture.event.sequence);
      // Unknown envelope field 2000 = 123; real Protobuf forward compatibility.
      const extended=new Uint8Array([...bytes,0x80,0x7d,0x7b]); expect(decodeWire(extended,fixture.request)).toEqual(result);
    }
  });
  it('rejects malformed, oversized, wrong-direction/version and unsafe numeric encodings', () => {
    for(const bytes of [new Uint8Array(),new Uint8Array([0x80]),new Uint8Array([0x12,0x03,0x1a,0x01,0xff,0xc0,0x3e,0x02]),new Uint8Array(MAX_FRAME_BYTES+1),Envelope.encode({version:1,heartbeatResponse:{}}).finish(),Envelope.encode({version:2}).finish()]) expect(()=>decodeServerEvent(bytes)).toThrow();
    expect(()=>decodeServerEvent(encodeClientEvent({type:'heartbeat'}))).toThrow();
    expect(()=>encodeClientEvent({type:'history',kind:'user',id:'peer',after:'9223372036854775808',limit:50,requestId:'x'})).toThrow();
    expect(()=>encodeServerEvent({type:'sent',sequence:9007199254740992})).toThrow();
    expect(()=>encodeClientEvent({type:'message',toUserId:'peer',message:'x'.repeat(MAX_FRAME_BYTES),requestId:'x'})).toThrow();
    expect(compareInt('9007199254740993','9007199254740992')).toBe(1); expect(maxInt('9007199254740993','9223372036854775807')).toBe('9223372036854775807');
    expect(integer('9223372036854775807')).toBe(9223372036854775807n);
  });
});
