CREATE TABLE xim_users (user_id VARCHAR(64) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE);
CREATE TABLE xim_groups (group_id VARCHAR(64) PRIMARY KEY);
CREATE TABLE xim_members (group_id VARCHAR(64) NOT NULL REFERENCES xim_groups(group_id), user_id VARCHAR(64) NOT NULL REFERENCES xim_users(user_id), PRIMARY KEY(group_id, user_id));
CREATE TABLE xim_messages (seq __SEQUENCE__ PRIMARY KEY, message_id VARCHAR(64) NOT NULL UNIQUE, sender_id VARCHAR(64) NOT NULL REFERENCES xim_users(user_id), recipient_id VARCHAR(64) REFERENCES xim_users(user_id), group_id VARCHAR(64) REFERENCES xim_groups(group_id), body TEXT NOT NULL, sent_at BIGINT NOT NULL, CHECK ((recipient_id IS NOT NULL AND group_id IS NULL) OR (recipient_id IS NULL AND group_id IS NOT NULL)));
CREATE INDEX xim_messages_private ON xim_messages(sender_id, recipient_id, seq);
CREATE INDEX xim_messages_recipient ON xim_messages(recipient_id, sender_id, seq);
CREATE INDEX xim_messages_group ON xim_messages(group_id, seq);
CREATE INDEX xim_members_user ON xim_members(user_id, group_id);
