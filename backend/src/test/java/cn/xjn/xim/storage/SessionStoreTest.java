package cn.xjn.xim.storage;

import cn.xjn.xim.auth.SessionTokens;
import org.junit.Test;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.Comparator;
import static org.junit.Assert.*;

public class SessionStoreTest {
    public static void sessionContract(DatabaseConfig config) {
        String name="session_"+java.util.UUID.randomUUID().toString().replace("-", "").substring(0,12);
        String id,token;
        try (var store=new ChatStore(config,false)) {
            id=store.register(name,"original-password").getUserId();
            var first=store.issueSession(id); assertEquals(43,first.ticket().length());
            var cookie=store.redeemTicket(first.ticket()); assertNotNull(cookie); token=cookie.token();
            assertNull(store.redeemTicket(first.ticket())); assertNull(store.resumeSession(first.ticket()));
            assertEquals(id,store.resumeSession(token).user().getUserId());
            assertNull(store.resumeSession("forged"));
        }
        try (var store=new ChatStore(config,false)) {
            assertEquals(id,store.resumeSession(token).user().getUserId());
            var second=store.issueSession(id); assertNull(store.resumeSession(token));
            var cookie=store.redeemTicket(second.ticket()); assertNotNull(cookie);
            store.changePassword(id,"original-password","changed-password");
            assertNull(store.resumeSession(cookie.token())); assertNull(store.sessionCredential(second.sessionId()));
            var third=store.issueSession(id); var thirdCookie=store.redeemTicket(third.ticket());
            store.revokeSession(third.sessionId()); assertNull(store.resumeSession(thirdCookie.token()));
            var fourth=store.issueSession(id); var fourthCookie=store.redeemTicket(fourth.ticket());
            store.revokeSessions(id); assertNull(store.resumeSession(fourthCookie.token()));
            var fifth=store.issueSession(id); var fifthCookie=store.redeemTicket(fifth.ticket());
            store.provisionAccount(name,"operator-password"); assertNull(store.resumeSession(fifthCookie.token()));
            assertEquals(id,store.authenticate(name,"operator-password").getUserId());
        }
    }
    @Test public void sessionsSurviveRestartAndRespectPasswordEpochLogoutAndNewLogin() throws Exception { withFile(SessionStoreTest::sessionContract); }
    @Test public void v4MigrationPreservesDataAndStoresOnlyHashesWithSingleUseTickets() throws Exception {
        withFile(config -> {
            String id;
            try (var store=new ChatStore(config,false)) { id=store.register("migration_user","private-password").getUserId(); }
            try(var conn=DriverManager.getConnection(config.url());var sql=conn.createStatement()) {
                sql.execute("ALTER TABLE xim_users DROP COLUMN display_name");sql.execute("DROP TABLE xim_sessions");sql.execute("DELETE FROM xim_schema_version WHERE version>=5");
            } catch(Exception failure){throw new AssertionError(failure);}
            try(var store=new ChatStore(config,false)) {
                assertEquals(id,store.authenticate("migration_user","private-password").getUserId());
                var grant=store.issueSession(id);var cookie=store.redeemTicket(grant.ticket());
                try(var conn=DriverManager.getConnection(config.url());var sql=conn.createStatement();var rows=sql.executeQuery("SELECT token_hash,ticket_hash FROM xim_sessions")) {
                    assertTrue(rows.next());assertEquals(SessionTokens.hash(cookie.token()),rows.getString(1));assertNull(rows.getString(2));
                } catch(Exception failure){throw new AssertionError(failure);}
            }
        });
    }
    @Test public void expiredCredentialAndTicketCannotBeUsed() throws Exception {
        withFile(config -> {
            try(var store=new ChatStore(config,false)) {
                var id=store.register("expiry_user","private-password").getUserId();
                var grant=store.issueSession(id);
                try(var conn=DriverManager.getConnection(config.url());var sql=conn.createStatement()) {sql.executeUpdate("UPDATE xim_sessions SET ticket_expires_at=0");}
                catch(Exception failure){throw new AssertionError(failure);}
                assertNull(store.redeemTicket(grant.ticket()));
                var next=store.issueSession(id);var cookie=store.redeemTicket(next.ticket());
                try(var conn=DriverManager.getConnection(config.url());var sql=conn.createStatement()) {sql.executeUpdate("UPDATE xim_sessions SET expires_at=0");}
                catch(Exception failure){throw new AssertionError(failure);}
                assertNull(store.resumeSession(cookie.token()));
            }
        });
    }
    private static void withFile(java.util.function.Consumer<DatabaseConfig> work) throws Exception {
        var dir=Files.createTempDirectory("xim-sessions-");
        try {work.accept(DatabaseConfig.sqlite(dir.resolve("chat.db")));}
        finally {try(var paths=Files.walk(dir)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
