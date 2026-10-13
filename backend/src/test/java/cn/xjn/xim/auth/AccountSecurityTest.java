package cn.xjn.xim.auth;

import cn.xjn.xim.protocol.request.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class AccountSecurityTest {
    @Test public void hashesUseIndependentSaltsAndNeverExposePasswords() {
        String secret = "unique-secret-password";
        String first = PasswordHash.hash(secret), second = PasswordHash.hash(secret);
        assertNotEquals(first, second); assertFalse(first.contains(secret));
        assertTrue(PasswordHash.verify(secret, first)); assertTrue(PasswordHash.verify(secret, second));
        assertFalse(PasswordHash.verify("wrong", first)); assertFalse(PasswordHash.verify(secret, null));
        for (String invalid : new String[]{"plain", "pbkdf2-sha256$1$bad$bad", "pbkdf2-sha256$999999999$bad$bad"})
            assertFalse(PasswordHash.verify(secret, invalid));
        var login = new LoginRequestPacket(); login.setPassword(secret);
        var register = new RegisterRequestPacket(); register.setPassword(secret);
        var change = new ChangePasswordRequestPacket(); change.setCurrentPassword(secret); change.setNewPassword(secret);
        assertFalse(login.toString().contains(secret)); assertFalse(register.toString().contains(secret)); assertFalse(change.toString().contains(secret));
    }
    @Test public void attemptsExpireAndCannotGrowWithoutBound() {
        AtomicLong clock = new AtomicLong(10);
        AttemptLimiter limiter = new AttemptLimiter(clock::get);
        for (int i = 0; i < 5; i++) { assertTrue(limiter.allowed("user", 5)); limiter.record("user"); }
        assertFalse(limiter.allowed("user", 5)); assertTrue(limiter.allowed("other", 5));
        clock.addAndGet(300_000); assertTrue(limiter.allowed("user", 5));
        for (int i = 0; i < 10_000; i++) limiter.record("bounded" + i);
        assertFalse(limiter.allowed("overflow", 5));
        clock.addAndGet(300_000); assertTrue(limiter.allowed("overflow", 5));
    }
    @Test public void demoRequiresExplicitOptIn() {
        String previous = System.getProperty("xim.auth.demo");
        try {
            System.setProperty("xim.auth.demo", "false"); assertFalse(AccountPolicy.demoEnabled());
            System.setProperty("xim.auth.demo", "true"); assertTrue(AccountPolicy.demoEnabled());
        } finally { if (previous == null) System.clearProperty("xim.auth.demo"); else System.setProperty("xim.auth.demo", previous); }
    }
}
