package cn.xjn.xim.server.handler;

import cn.xjn.xim.auth.AccountPolicy;
import cn.xjn.xim.protocol.request.*;
import cn.xjn.xim.protocol.response.*;
import cn.xjn.xim.storage.Storage;
import cn.xjn.xim.support.ChannelTestSupport;
import cn.xjn.xim.util.SessionManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountRequestHandlerTest extends ChannelTestSupport {
    @Test public void registersChangesPasswordRevokesIdentityAndClearsRequestSecrets() {
        String username = "handler_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var channel = channel(LoginRequestHandler.INSTANCE, AccountRequestHandler.INSTANCE, IMServerHandler.INSTANCE);
        var register = new RegisterRequestPacket(); register.setUsername(username); register.setPassword("first-secret"); register.setRequestId("reg");
        channel.writeInbound(register);
        RegisterResponsePacket created = channel.readOutbound(); assertTrue(created.isSuccess()); assertEquals("reg", created.getRequestId());
        assertNull(register.getPassword()); assertFalse(SessionManager.hasLogin(channel));
        var login = new LoginRequestPacket(); login.setUsername(username); login.setPassword("first-secret"); channel.writeInbound(login);
        LoginResponsePacket logged = channel.readOutbound(); assertTrue(logged.isSuccess()); assertNull(login.getPassword());
        long version = Storage.get().credentialVersion(logged.getUserId());
        var change = new ChangePasswordRequestPacket(); change.setCurrentPassword("wrong-secret"); change.setNewPassword("second-secret"); change.setRequestId("bad");
        channel.writeInbound(change);
        ChangePasswordResponsePacket rejected = channel.readOutbound(); assertFalse(rejected.isSuccess()); assertEquals(AccountPolicy.INVALID_CREDENTIALS, rejected.getReason());
        assertTrue(SessionManager.hasLogin(channel)); assertEquals(version, Storage.get().credentialVersion(logged.getUserId()));
        change = new ChangePasswordRequestPacket(); change.setCurrentPassword("first-secret"); change.setNewPassword("second-secret"); change.setRequestId("change");
        channel.writeInbound(change);
        ChangePasswordResponsePacket changed = channel.readOutbound(); assertTrue(changed.isSuccess()); assertEquals("change", changed.getRequestId());
        assertNull(change.getCurrentPassword()); assertNull(change.getNewPassword());
        assertFalse(SessionManager.hasLogin(channel)); assertFalse(channel.isActive()); assertNull(SessionManager.getChannel(logged.getUserId()));
        assertEquals(version + 1, Storage.get().credentialVersion(logged.getUserId()));
        assertNull(Storage.get().authenticate(username, "first-secret"));
        assertEquals(logged.getUserId(), Storage.get().authenticate(username, "second-secret").getUserId());
    }
    @Test public void anonymousChangesAndIllegalRegistrationsReturnSafeErrors() {
        var channel = channel(AccountRequestHandler.INSTANCE);
        var change = new ChangePasswordRequestPacket(); change.setCurrentPassword("private-secret"); change.setNewPassword("new-private-secret");
        channel.writeInbound(change); ChangePasswordResponsePacket denied = channel.readOutbound();
        assertFalse(denied.isSuccess()); assertEquals("请先登录", denied.getReason());
        var register = new RegisterRequestPacket(); register.setUsername("bad\nusername"); register.setPassword("private-secret");
        channel.writeInbound(register); RegisterResponsePacket invalid = channel.readOutbound();
        assertFalse(invalid.isSuccess()); assertFalse(invalid.getReason().contains("private-secret")); assertFalse(invalid.getReason().contains("bad\nusername"));
    }
}
