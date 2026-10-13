ALTER TABLE xim_messages ADD COLUMN client_message_id VARCHAR(80);
CREATE UNIQUE INDEX xim_messages_client_id ON xim_messages(sender_id, client_message_id);
