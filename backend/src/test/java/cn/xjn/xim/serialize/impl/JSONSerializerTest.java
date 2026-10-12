package cn.xjn.xim.serialize.impl;

import cn.xjn.xim.protocol.request.LoginRequestPacket;
import cn.xjn.xim.protocol.response.ListGroupMemberResponsePacket;
import cn.xjn.xim.serialize.SerializerAlgorithm;
import cn.xjn.xim.session.Session;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class JSONSerializerTest {
    private final JSONSerializer serializer = new JSONSerializer();

    @Test
    public void identifiesJsonAlgorithm() {
        assertEquals(SerializerAlgorithm.JSON, serializer.getSerializerAlgorithm());
    }

    @Test
    public void roundTripsUnicodeAndEscapedCredentials() {
        LoginRequestPacket source = new LoginRequestPacket();
        source.setUsername("用户 🌍");
        source.setPassword("quote\" newline\n slash\\");
        byte[] bytes = serializer.serialize(source);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("用户"));
        LoginRequestPacket decoded = serializer.deserialize(bytes, LoginRequestPacket.class);
        assertEquals(source.getUsername(), decoded.getUsername());
        assertEquals(source.getPassword(), decoded.getPassword());
    }

    @Test
    public void deserializesNestedSessionsIntoSessionObjects() {
        ListGroupMemberResponsePacket source = new ListGroupMemberResponsePacket();
        source.setGroupId("group-1");
        source.setSessions(List.of(new Session("user-1", "用户")));
        ListGroupMemberResponsePacket decoded = serializer.deserialize(serializer.serialize(source),
                ListGroupMemberResponsePacket.class);
        assertEquals("group-1", decoded.getGroupId());
        assertEquals(source.getSessions(), decoded.getSessions());
    }

    @Test
    public void rejectsMalformedJson() {
        assertThrows(com.alibaba.fastjson.JSONException.class,
                () -> serializer.deserialize("{invalid".getBytes(StandardCharsets.UTF_8), LoginRequestPacket.class));
    }
}
