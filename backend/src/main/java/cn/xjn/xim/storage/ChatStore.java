package cn.xjn.xim.storage;

import cn.xjn.xim.session.Session;
import cn.xjn.xim.auth.AccountPolicy;
import cn.xjn.xim.auth.PasswordHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Small MVP repository. One connection serializes transactions; invoked on the storage executor. */
public final class ChatStore implements AutoCloseable {
    // JavaBean DTOs keep Fastjson 1.x TCP and WebSocket serialization compatible.
    @lombok.Data @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class StoredMessage {
        private String id;
        private long sequence;
        private String fromUserId;
        private String fromUsername;
        private String message;
        private long time;
        private String clientMessageId;
        public StoredMessage(String id, long sequence, String fromUserId, String fromUsername, String message, long time) {
            this(id, sequence, fromUserId, fromUsername, message, time, null);
        }
    }
    @lombok.Data @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class Conversation {
        private String kind;
        private String id;
        private String name;
        private StoredMessage lastMessage;
        private long lastSequence;
        private long lastTime;
        private long readSequence;
        private long unread;
        public Conversation(String kind, String id, String name) {
            this(kind, id, name, null, 0, 0, 0, 0);
        }
    }
    public record SaveResult(StoredMessage message, boolean inserted) {}
    public record History(List<StoredMessage> messages, String nextBefore, boolean hasMore, String nextAfter, long syncCursor) {}
    private final Connection connection;
    private final boolean demoEnabled;

    public ChatStore(DatabaseConfig config) {
        this(config, AccountPolicy.demoEnabled());
    }
    public ChatStore(DatabaseConfig config, boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
        try {
            Properties properties = new Properties();
            if (config.type().equals("sqlite")) {
                Path file = Path.of(config.url().substring("jdbc:sqlite:".length()));
                if (file.getParent() != null) Files.createDirectories(file.getParent());
            } else if (config.type().equals("pgsql") && config.url().startsWith("jdbc:postgresql:")) {
                properties.setProperty("user", config.username());
                properties.setProperty("password", config.password());
                properties.setProperty("connectTimeout", "5");
                properties.setProperty("socketTimeout", "15");
            } else throw new IllegalArgumentException("Invalid database configuration");
            connection = DriverManager.getConnection(config.url(), properties);
            try {
                if (config.type().equals("sqlite")) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("PRAGMA foreign_keys=ON");
                        statement.execute("PRAGMA busy_timeout=5000");
                        statement.execute("PRAGMA journal_mode=WAL");
                    }
                }
                migrate(config.type());
                if (demoEnabled) seedDemoAccounts();
            } catch (Exception failure) { connection.close(); throw failure; }
        } catch (Exception failure) {
            // Do not include the URL/password or driver exception text in user-visible errors.
            throw new IllegalStateException("Database initialization failed", failure);
        }
    }

    private void migrate(String type) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS xim_schema_version (version INTEGER PRIMARY KEY)");
        }
        int version;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM xim_schema_version")) {
            rs.next(); version = rs.getInt(1);
        }
        String[] migrations = {"V1__chat.sql", "V2__message_retry.sql", "V3__accounts.sql", "V4__conversation_reads.sql", "V5__sessions.sql", "V6__user_directory.sql"};
        if (version > migrations.length) throw new IllegalStateException("Database schema is newer than this application");
        for (int next = version + 1; next <= migrations.length; next++) {
            final int migrationVersion = next;
            try (var resource = ChatStore.class.getResourceAsStream("/db/migration/" + migrations[next - 1])) {
                Objects.requireNonNull(resource, "Missing migration");
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("__SEQUENCE__", type.equals("sqlite") ? "INTEGER" : "BIGSERIAL");
                transaction(() -> {
                    try (Statement statement = connection.createStatement()) {
                        for (String command : sql.split(";")) if (!command.isBlank()) statement.execute(command);
                        statement.executeUpdate("INSERT INTO xim_schema_version(version) VALUES (" + migrationVersion + ")");
                    }
                    return null;
                });
            }
        }
    }

    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try { T result = work.run(); connection.commit(); return result; }
        catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        finally { connection.setAutoCommit(true); }
    }
    private <T> T query(Work<T> work) {
        try { return work.run(); }
        catch (SQLException failure) { throw new IllegalStateException("Database operation failed", failure); }
    }
    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }
    private int update(String sql, Object... args) throws SQLException {
        try (var statement = prepare(sql, args)) { return statement.executeUpdate(); }
    }
    private boolean exists(String sql, Object... args) throws SQLException {
        try (var statement = prepare(sql, args); var rs = statement.executeQuery()) { return rs.next(); }
    }

    /** Internal identity provisioning for legacy fixtures; never authenticates or sets a password. */
    public synchronized Session user(String username) {
        return query(() -> {
            update("INSERT INTO xim_users(user_id, username) VALUES (?, ?) ON CONFLICT(username) DO NOTHING", UUID.randomUUID().toString(), username);
            try (var statement = prepare("SELECT user_id FROM xim_users WHERE username=?", username); var rs = statement.executeQuery()) {
                rs.next(); return new Session(rs.getString(1), username);
            }
        });
    }
    private void seedDemoAccounts() throws SQLException {
        for (String username : List.of("root", "aaa", "bbb", "ccc")) {
            user(username);
            if (exists("SELECT 1 FROM xim_users WHERE username=? AND password_hash IS NULL", username))
                update("UPDATE xim_users SET password_hash=?,demo_account=1,credential_version=credential_version+1 WHERE username=? AND password_hash IS NULL",
                        PasswordHash.hash(username), username);
        }
    }
    public synchronized Session register(String username, String password) {
        return register(username, password, null);
    }
    public synchronized Session register(String username, String password, String displayName) {
        AccountPolicy.validate(username, password);
        if (displayName != null && (displayName.isBlank() || displayName.length() > 64 || displayName.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("显示名须为 1–64 个字符，不能全为空白或包含控制字符");
        return query(() -> {
            String id = UUID.randomUUID().toString();
            if (update("INSERT INTO xim_users(user_id,username,password_hash,credential_version,display_name) VALUES (?,?,?,1,?) ON CONFLICT(username) DO NOTHING",
                    id, username, PasswordHash.hash(password), displayName == null ? null : displayName.strip()) == 0)
                throw new IllegalArgumentException("账号已存在，请选择其他账号");
            return new Session(id, username);
        });
    }
    public synchronized Session authenticate(String username, String password) {
        if (!AccountPolicy.validUsername(username) || !AccountPolicy.validLoginPassword(password)) return null;
        return query(() -> {
            try (var statement = prepare("SELECT user_id,password_hash,demo_account FROM xim_users WHERE username=?", username);
                 var rs = statement.executeQuery()) {
                if (!rs.next()) { PasswordHash.verify(password, null); return null; }
                String hash = !demoEnabled && rs.getInt(3) != 0 ? null : rs.getString(2);
                return PasswordHash.verify(password, hash) ? new Session(rs.getString(1), username) : null;
            }
        });
    }
    /** Persisted revocation epoch: future session credentials must match this value on resume. */
    public synchronized long credentialVersion(String userId) {
        return query(() -> {
            try (var statement = prepare("SELECT credential_version FROM xim_users WHERE user_id=?", userId); var rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("账号不存在");
                return rs.getLong(1);
            }
        });
    }
    public synchronized long changePassword(String userId, String currentPassword, String newPassword) {
        AccountPolicy.validatePassword(newPassword);
        if (Objects.equals(currentPassword, newPassword)) throw new IllegalArgumentException("新密码必须与当前密码不同");
        return query(() -> transaction(() -> {
            String oldHash;
            try (var statement = prepare("SELECT password_hash,demo_account FROM xim_users WHERE user_id=?", userId); var rs = statement.executeQuery()) {
                oldHash = rs.next() && (demoEnabled || rs.getInt(2) == 0) ? rs.getString(1) : null;
            }
            if (!PasswordHash.verify(currentPassword, oldHash)) throw new IllegalArgumentException(AccountPolicy.INVALID_CREDENTIALS);
            if (update("UPDATE xim_users SET password_hash=?,demo_account=0,credential_version=credential_version+1 WHERE user_id=? AND password_hash=?",
                    PasswordHash.hash(newPassword), userId, oldHash) != 1)
                throw new IllegalArgumentException("密码已发生变化，请重新登录");
            return credentialVersion(userId);
        }));
    }
    /** Local administrator only: server must be stopped. Keeps the existing identity and history. */
    public synchronized Session provisionAccount(String username, String password) {
        AccountPolicy.validate(username, password);
        return query(() -> transaction(() -> {
            Session session = user(username);
            update("UPDATE xim_users SET password_hash=?,demo_account=0,credential_version=credential_version+1 WHERE user_id=?",
                    PasswordHash.hash(password), session.getUserId());
            return session;
        }));
    }
    public record SessionGrant(String sessionId, String ticket, long expiresAt) {}
    public record Credential(String sessionId, Session user, long expiresAt) {}
    public record SessionCookie(String token, long expiresAt) {}
    public synchronized SessionGrant issueSession(String userId) {
        String id = UUID.randomUUID().toString(), ticket = cn.xjn.xim.auth.SessionTokens.random();
        long now = System.currentTimeMillis(), expiry = now + cn.xjn.xim.auth.SessionTokens.ttlMillis();
        return query(() -> transaction(() -> {
            update("UPDATE xim_sessions SET revoked=1 WHERE user_id=?", userId);
            update("DELETE FROM xim_sessions WHERE expires_at<?", now);
            update("INSERT INTO xim_sessions(session_id,user_id,credential_version,ticket_hash,ticket_expires_at,expires_at) VALUES (?,?,?,?,?,?)",
                id,userId,credentialVersion(userId),cn.xjn.xim.auth.SessionTokens.hash(ticket),now+30000,expiry);
            return new SessionGrant(id,ticket,expiry);
        }));
    }
    /** Single-use 30s ticket from authenticated WSS becomes a fresh HttpOnly cookie. */
    public synchronized SessionCookie redeemTicket(String ticket) {
        String hash = cn.xjn.xim.auth.SessionTokens.hash(ticket);
        if (hash == null) return null;
        return query(() -> transaction(() -> {
            long now = System.currentTimeMillis();
            try (var statement = prepare("SELECT s.session_id,s.expires_at FROM xim_sessions s JOIN xim_users u ON u.user_id=s.user_id WHERE s.ticket_hash=? AND s.ticket_expires_at>? AND s.expires_at>? AND s.revoked=0 AND s.credential_version=u.credential_version AND (u.demo_account=0 OR ?=1)",hash,now,now,demoEnabled?1:0); var rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                String id = rs.getString(1); long expiry = rs.getLong(2);
                String token = cn.xjn.xim.auth.SessionTokens.random();
                if (update("UPDATE xim_sessions SET token_hash=?,ticket_hash=NULL WHERE session_id=? AND ticket_hash=? AND revoked=0", cn.xjn.xim.auth.SessionTokens.hash(token),id,hash) != 1) return null;
                return new SessionCookie(token,expiry);
            }
        }));
    }
    public synchronized Credential resumeSession(String token) {
        String hash = cn.xjn.xim.auth.SessionTokens.hash(token);
        return hash == null ? null : sessionCredential("s.token_hash=?",hash);
    }
    public synchronized Credential sessionCredential(String id) { return sessionCredential("s.session_id=?",id); }
    private Credential sessionCredential(String predicate, String value) {
        return query(() -> {
            try (var statement = prepare("SELECT s.session_id,u.user_id,u.username,s.expires_at FROM xim_sessions s JOIN xim_users u ON u.user_id=s.user_id WHERE " + predicate + " AND s.expires_at>? AND s.revoked=0 AND s.credential_version=u.credential_version AND (u.demo_account=0 OR ?=1)",value,System.currentTimeMillis(),demoEnabled?1:0); var rs = statement.executeQuery()) {
                return rs.next() ? new Credential(rs.getString(1),new Session(rs.getString(2),rs.getString(3)),rs.getLong(4)) : null;
            }
        });
    }
    public synchronized void revokeSession(String id) { query(() -> update("UPDATE xim_sessions SET revoked=1 WHERE session_id=?",id)); }
    public synchronized void revokeSessions(String userId) { query(() -> update("UPDATE xim_sessions SET revoked=1 WHERE user_id=?",userId)); }
    public synchronized boolean hasUser(String id) {
        return query(() -> exists("SELECT 1 FROM xim_users WHERE user_id=?", id));
    }
    /** Public directory DTO: credentials and session data never enter this result. */
    @lombok.Data @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class Contact {
        private String userId;
        private String username;
        private String displayName;
    }
    public record Contacts(List<Contact> users, String nextAfter, boolean hasMore) {}
    public synchronized Contacts contacts(String self, String search, String after, int limit) {
        if (!hasUser(self)) throw new IllegalArgumentException("请先登录");
        if (search == null || search.length() > 64 || search.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("搜索词须为最多 64 个字符");
        if (after != null && (after.isBlank() || after.length() > 64 || after.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("联系人游标无效");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("联系人页大小必须为 1 到 50");
        String pattern = "%" + search.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return query(() -> {
            List<Contact> users = new ArrayList<>();
            try (var statement = prepare("SELECT user_id,username,COALESCE(display_name,username) FROM xim_users WHERE user_id<>? AND user_id>? AND password_hash IS NOT NULL AND (demo_account=0 OR ?=1) AND (LOWER(username) LIKE ? ESCAPE '!' OR LOWER(COALESCE(display_name,username)) LIKE ? ESCAPE '!') ORDER BY user_id LIMIT ?",
                    self, after == null ? "" : after, demoEnabled ? 1 : 0, pattern, pattern, limit + 1); var rs = statement.executeQuery()) {
                while (rs.next()) users.add(new Contact(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            boolean more = users.size() > limit;
            if (more) users.removeLast();
            return new Contacts(users, more ? users.getLast().getUserId() : null, more);
        });
    }
    public synchronized boolean hasGroup(String id) {
        return query(() -> exists("SELECT 1 FROM xim_groups WHERE group_id=?", id));
    }
    public synchronized boolean isMember(String groupId, String userId) {
        return query(() -> exists("SELECT 1 FROM xim_members WHERE group_id=? AND user_id=?", groupId, userId));
    }
    public synchronized void createGroup(String id, Collection<String> users) {
        query(() -> transaction(() -> {
            update("INSERT INTO xim_groups(group_id) VALUES (?)", id);
            for (String user : new LinkedHashSet<>(users)) update("INSERT INTO xim_members(group_id,user_id) VALUES (?,?)", id, user);
            return null;
        }));
    }
    public synchronized void join(String group, String user) {
        if (!hasGroup(group)) throw new IllegalArgumentException("Group does not exist");
        query(() -> transaction(() -> {
            if (update("INSERT INTO xim_members(group_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING", group, user) == 1) {
                // Joining/rejoining retains history but starts unread tracking at the joining boundary.
                long latest = latestSequence(user, "group", group);
                writeRead(user, "group", group, latest);
            }
            return null;
        }));
    }
    public synchronized void quit(String group, String user) {
        requireMember(group, user);
        query(() -> update("DELETE FROM xim_members WHERE group_id=? AND user_id=?", group, user));
    }
    public synchronized void requireMember(String group, String user) {
        if (!isMember(group, user)) throw new IllegalArgumentException("请先加入该群组");
    }
    public synchronized List<Session> members(String group) {
        return query(() -> {
            List<Session> result = new ArrayList<>();
            try (var statement = prepare("SELECT u.user_id,u.username FROM xim_members m JOIN xim_users u ON u.user_id=m.user_id WHERE m.group_id=? ORDER BY u.username,u.user_id", group); var rs = statement.executeQuery()) {
                while (rs.next()) result.add(new Session(rs.getString(1), rs.getString(2)));
            }
            return result;
        });
    }
    public synchronized List<Conversation> conversations(String user) {
        return query(() -> {
            List<Conversation> result = new ArrayList<>();
            try (var statement = prepare("SELECT DISTINCT u.user_id,COALESCE(u.display_name,u.username) AS contact_name FROM xim_messages m JOIN xim_users u ON u.user_id=CASE WHEN m.sender_id=? THEN m.recipient_id ELSE m.sender_id END WHERE m.group_id IS NULL AND (m.sender_id=? OR m.recipient_id=?) ORDER BY contact_name,u.user_id", user,user,user); var rs = statement.executeQuery()) {
                while (rs.next()) result.add(new Conversation("user", rs.getString(1), rs.getString(2)));
            }
            try (var statement = prepare("SELECT group_id FROM xim_members WHERE user_id=? ORDER BY group_id", user); var rs = statement.executeQuery()) {
                while (rs.next()) result.add(new Conversation("group", rs.getString(1), "群聊 " + rs.getString(1)));
            }
            for (var chat : result) fillSummary(user, chat);
            result.sort(Comparator.comparingLong(Conversation::getLastSequence).reversed()
                    .thenComparing(Conversation::getKind).thenComparing(Conversation::getId));
            return result;
        });
    }

    private void requireConversation(String user, String kind, String id) {
        if ("group".equals(kind)) requireMember(id, user);
        else if (!"user".equals(kind)) throw new IllegalArgumentException("会话类型无效");
        else if (!hasUser(id) || id.equals(user)) throw new IllegalArgumentException("收件人无效");
    }
    private String messagePredicate(String kind) {
        return kind.equals("group") ? "m.group_id=?" : "m.group_id IS NULL AND ((m.sender_id=? AND m.recipient_id=?) OR (m.sender_id=? AND m.recipient_id=?))";
    }
    private Object[] messageArgs(String user, String kind, String id, Object... tail) {
        List<Object> args = new ArrayList<>(kind.equals("group") ? List.of(id) : List.of(user,id,id,user));
        args.addAll(Arrays.asList(tail)); return args.toArray();
    }
    private long latestSequence(String user, String kind, String id) throws SQLException {
        try (var statement = prepare("SELECT COALESCE(MAX(m.seq),0) FROM xim_messages m WHERE " + messagePredicate(kind), messageArgs(user,kind,id)); var rs = statement.executeQuery()) {
            rs.next(); return rs.getLong(1);
        }
    }
    public synchronized long syncCursor() {
        return query(() -> {
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT COALESCE(MAX(seq),0) FROM xim_messages")) {
                rs.next(); return rs.getLong(1);
            }
        });
    }
    private void fillSummary(String user, Conversation chat) throws SQLException {
        try (var statement = prepare("SELECT read_seq FROM xim_conversation_reads WHERE user_id=? AND kind=? AND conversation_id=?",user,chat.getKind(),chat.getId()); var rs = statement.executeQuery()) {
            if (rs.next()) chat.setReadSequence(rs.getLong(1));
        }
        try (var statement = prepare("SELECT m.message_id,m.seq,m.sender_id,u.username,m.body,m.sent_at,m.client_message_id FROM xim_messages m JOIN xim_users u ON u.user_id=m.sender_id WHERE " + messagePredicate(chat.getKind()) + " ORDER BY m.seq DESC LIMIT 1", messageArgs(user,chat.getKind(),chat.getId())); var rs = statement.executeQuery()) {
            if (rs.next()) {
                var last = storedMessage(rs); chat.setLastMessage(last);
                chat.setLastSequence(last.getSequence()); chat.setLastTime(last.getTime());
            }
        }
        try (var statement = prepare("SELECT COUNT(*) FROM xim_messages m WHERE " + messagePredicate(chat.getKind()) + " AND m.seq>? AND m.sender_id<>?",messageArgs(user,chat.getKind(),chat.getId(),chat.getReadSequence(),user)); var rs = statement.executeQuery()) {
            rs.next(); chat.setUnread(rs.getLong(1));
        }
    }
    private StoredMessage storedMessage(ResultSet rs) throws SQLException {
        return new StoredMessage(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7));
    }
    private void writeRead(String user, String kind, String id, long sequence) throws SQLException {
        update("INSERT INTO xim_conversation_reads(user_id,kind,conversation_id,read_seq) VALUES (?,?,?,?) ON CONFLICT(user_id,kind,conversation_id) DO UPDATE SET read_seq=CASE WHEN excluded.read_seq>xim_conversation_reads.read_seq THEN excluded.read_seq ELSE xim_conversation_reads.read_seq END",user,kind,id,sequence);
    }
    public synchronized Conversation markRead(String user, String kind, String id, String sequence) {
        requireConversation(user,kind,id);
        final long cursor;
        try { cursor = Long.parseLong(sequence); }
        catch (NumberFormatException bad) { throw new IllegalArgumentException("阅读位置无效"); }
        if (cursor <= 0) throw new IllegalArgumentException("阅读位置无效");
        return query(() -> transaction(() -> {
            // Never allow a future or unrelated sequence to suppress unseen conversation messages.
            if (!exists("SELECT 1 FROM xim_messages m WHERE " + messagePredicate(kind) + " AND m.seq=?", messageArgs(user,kind,id,cursor)))
                throw new IllegalArgumentException("阅读位置不属于该会话");
            writeRead(user,kind,id,cursor);
            String name = "群聊 " + id;
            if (kind.equals("user")) {
                try (var statement = prepare("SELECT COALESCE(display_name,username) FROM xim_users WHERE user_id=?",id); var rs = statement.executeQuery()) { rs.next(); name = rs.getString(1); }
            }
            var chat = new Conversation(kind,id,name); fillSummary(user,chat); return chat;
        }));
    }
    /** Legacy TCP requests without a client ID remain independent sends. */
    public synchronized StoredMessage save(Session sender, String recipient, String group, String body) {
        return save(sender, recipient, group, body, null).message();
    }
    public synchronized SaveResult save(Session sender, String recipient, String group, String body, String clientMessageId) {
        if (clientMessageId != null && (clientMessageId.isBlank() || clientMessageId.length() > 80))
            throw new IllegalArgumentException("请求编号无效");
        if (body == null || body.isBlank() || body.length() > 4000) throw new IllegalArgumentException("消息不能为空，且长度不能超过 4000");
        if (group != null) requireMember(group, sender.getUserId());
        else if (!hasUser(recipient) || recipient.equals(sender.getUserId())) throw new IllegalArgumentException("收件人无效");
        String id = UUID.randomUUID().toString(); long time = System.currentTimeMillis();
        return query(() -> transaction(() -> {
            int inserted = update("INSERT INTO xim_messages(message_id,sender_id,recipient_id,group_id,body,sent_at,client_message_id) VALUES (?,?,?,?,?,?,?) ON CONFLICT(sender_id,client_message_id) DO NOTHING",
                    id,sender.getUserId(),recipient,group,body,time,clientMessageId);
            String predicate = clientMessageId == null ? "message_id=?" : "sender_id=? AND client_message_id=?";
            Object[] args = clientMessageId == null ? new Object[]{id} : new Object[]{sender.getUserId(),clientMessageId};
            try (var statement = prepare("SELECT message_id,seq,body,sent_at,recipient_id,group_id FROM xim_messages WHERE " + predicate,args); var rs = statement.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing saved message");
                if (!Objects.equals(body,rs.getString(3)) || !Objects.equals(recipient,rs.getString(5)) || !Objects.equals(group,rs.getString(6)))
                    throw new IllegalArgumentException("请求编号已用于其他消息，请勿更改内容或目标后重试");
                return new SaveResult(new StoredMessage(rs.getString(1),rs.getLong(2),sender.getUserId(),sender.getUsername(),rs.getString(3),rs.getLong(4),clientMessageId),inserted == 1);
            }
        }));
    }
    public synchronized History history(String user, String kind, String id, String before, int limit) {
        return history(user,kind,id,before,null,limit);
    }
    public synchronized History history(String user, String kind, String id, String before, String after, int limit) {
        requireConversation(user,kind,id);
        if (before != null && after != null) throw new IllegalArgumentException("历史游标不能同时使用 before 和 after");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("历史页大小必须为 1 到 100");
        boolean forward = after != null;
        long cursor = forward ? 0 : Long.MAX_VALUE;
        if (before != null || after != null) {
            try { cursor = Long.parseLong(forward ? after : before); } catch (NumberFormatException bad) { throw new IllegalArgumentException("历史游标无效"); }
            if (cursor < 0 || !forward && cursor == 0) throw new IllegalArgumentException("历史游标无效");
        }
        final long upper = cursor;
        return query(() -> {
            long snapshot = syncCursor();
            Object[] args = messageArgs(user,kind,id,upper,snapshot,limit+1);
            List<StoredMessage> result = new ArrayList<>();
            try (var statement = prepare("SELECT m.message_id,m.seq,m.sender_id,u.username,m.body,m.sent_at,m.client_message_id FROM xim_messages m JOIN xim_users u ON u.user_id=m.sender_id WHERE " + messagePredicate(kind) + " AND m.seq" + (forward ? ">?" : "<?") + " AND m.seq<=? ORDER BY m.seq " + (forward ? "ASC" : "DESC") + " LIMIT ?", args); var rs = statement.executeQuery()) {
                while (rs.next()) result.add(storedMessage(rs));
            }
            boolean more = result.size() > limit;
            if (more) result.remove(result.size()-1);
            if (!forward) Collections.reverse(result);
            return new History(result, result.isEmpty() ? null : Long.toString(result.getFirst().getSequence()), more,
                    result.isEmpty() ? after : Long.toString(result.getLast().getSequence()), snapshot);
        });
    }
    @Override public synchronized void close() { query(() -> { connection.close(); return null; }); }
}
