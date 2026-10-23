CREATE TABLE conversation_reads (
    conversation_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    last_read_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT conversation_reads_conversation_fk FOREIGN KEY (conversation_id)
        REFERENCES conversations(id) ON DELETE CASCADE,
    CONSTRAINT conversation_reads_user_fk FOREIGN KEY (user_id)
        REFERENCES app_users(id) ON DELETE CASCADE
);
