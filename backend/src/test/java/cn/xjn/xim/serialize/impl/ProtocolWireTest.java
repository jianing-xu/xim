package cn.xjn.xim.serialize.impl;

import cn.xjn.xim.protobuf.ProtocolWire;
import cn.xjn.xim.protobuf.XimWire;
import cn.xjn.xim.protocol.response.HistoryResponsePacket;
import cn.xjn.xim.storage.ChatStore;
import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.UnknownFieldSet;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ProtocolWireTest {
    @Test public void encodesTypedRequestsWithoutMutatingTheCaller() {
        var input=new JSONObject(); input.put("type","login"); input.put("username","用户"); input.put("password","private-test-password");
        byte[] first=ProtocolWire.encodeWeb(input,true); byte[] second=ProtocolWire.encodeWeb(input,true);
        assertEquals("login",input.getString("type")); assertArrayEquals(first,second);
        var wire=assertParsed(first); assertTrue(wire.hasLoginRequest()); assertEquals("用户",wire.getLoginRequest().getUsername());
    }
    @Test public void everyOperationHasATypedEnvelopeIncludingWebOnlySessionAndDirectoryFields() {
        var builder=XimWire.Envelope.newBuilder().setVersion(2);
        for(var field:XimWire.Envelope.getDescriptor().getFields()) {
            if(field.getNumber()==1000) continue;
            builder.clearPayload(); builder.setField(field,builder.newBuilderForField(field).build());
            var bytes=builder.build().toByteArray(); boolean request=field.getJsonName().endsWith("Request");
            var event=ProtocolWire.decodeWeb(bytes,request);
            assertEquals(field.getJsonName().replaceFirst("(Request|Response)$",""),event.getString("type"));
            assertArrayEquals(bytes,ProtocolWire.encodeWeb(event,request));
        }
    }
    @Test public void preservesUnicodeInt64NestedHistoryAndUnknownFields() {
        var message=new ChatStore.StoredMessage("id",Long.MAX_VALUE,"peer","用户","中文 🌏\n",123,"client-id");
        var packet=new HistoryResponsePacket(); packet.setKind("user");packet.setId("peer");packet.setRequestId("page");
        packet.setMessages(List.of(message));packet.setNextAfter(Long.toString(Long.MAX_VALUE));packet.setSyncCursor(Long.MAX_VALUE);
        byte[] bytes=ProtocolWire.encodePacket(packet);var parsed=assertParsed(bytes);
        assertEquals(Long.MAX_VALUE,parsed.getHistoryResponse().getMessages(0).getSequence());
        var unknown=UnknownFieldSet.newBuilder().addField(2000,UnknownFieldSet.Field.newBuilder().addVarint(123).build()).build();
        byte[] extended=parsed.toBuilder().setUnknownFields(unknown).build().toByteArray();
        var restored=ProtocolWire.decodePacket(extended,HistoryResponsePacket.class);
        assertEquals(Long.MAX_VALUE,restored.getMessages().getFirst().getSequence());assertEquals(message.getMessage(),restored.getMessages().getFirst().getMessage());
        var event=ProtocolWire.decodeWeb(extended,false);assertEquals(Long.toString(Long.MAX_VALUE),event.getString("syncCursor"));
        assertEquals(Long.toString(Long.MAX_VALUE),event.getJSONArray("messages").getJSONObject(0).getString("sequence"));
    }
    @Test public void rejectsWrongDirectionVersionMissingPayloadBrokenAndOversizeWithoutLeakingCredentials() {
        for(byte[] bytes:List.of(new byte[]{(byte)0x80},new byte[0],XimWire.Envelope.newBuilder().setVersion(2).build().toByteArray(),
                XimWire.Envelope.newBuilder().setVersion(1).setHeartbeatRequest(XimWire.EmptyRequest.getDefaultInstance()).build().toByteArray())) {
            var failure=assertThrows(IllegalArgumentException.class,()->ProtocolWire.decodeWeb(bytes,true));
            assertNull(failure.getCause());assertEquals("Invalid Protobuf payload",failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class,()->ProtocolWire.decodeWeb(XimWire.Envelope.newBuilder().setVersion(2).setHeartbeatResponse(XimWire.EmptyResponse.getDefaultInstance()).build().toByteArray(),true));
        var big=new JSONObject();big.put("type","message");big.put("toUserId","peer");big.put("message","x".repeat(65*1024));
        assertThrows(IllegalArgumentException.class,()->ProtocolWire.webFrame(big,true));
        var invalid=new byte[]{0x0a,0x03,0x0a,0x01,(byte)0xff,(byte)0xc0,0x3e,0x02}; // Invalid UTF-8 in a typed login username.
        assertThrows(IllegalArgumentException.class,()->ProtocolWire.decodeWeb(invalid,true));
    }
    @Test public void javaReadsSharedTypeScriptGoldenVectorsExactly() throws Exception {
        var fixtures=com.alibaba.fastjson.JSON.parseArray(java.nio.file.Files.readString(java.nio.file.Path.of("..","protocol","golden-vectors.json")));
        for(Object entry:fixtures) {
            var fixture=(JSONObject)entry; byte[] bytes=java.util.Base64.getDecoder().decode(fixture.getString("base64"));
            var event=ProtocolWire.decodeWeb(bytes,fixture.getBooleanValue("request"));
            assertEquals(fixture.getJSONObject("event"),event);
            var encoded=ProtocolWire.encodeWeb(event,fixture.getBooleanValue("request"));
            assertEquals(assertParsed(bytes),assertParsed(encoded));
        }
    }
    private XimWire.Envelope assertParsed(byte[] bytes) { try {return XimWire.Envelope.parseFrom(bytes);}catch(Exception failure){throw new AssertionError(failure);} }
}
