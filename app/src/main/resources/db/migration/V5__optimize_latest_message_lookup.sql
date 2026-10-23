-- The existing messages_by_conversation index is also required by the
-- conversation_id foreign key, so preserve it and add the ordering index.
CREATE INDEX messages_by_conversation_latest
    ON messages (conversation_id, created_at DESC, id DESC);
