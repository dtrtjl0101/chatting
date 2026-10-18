ALTER TABLE app_users
    ADD COLUMN user_code VARCHAR(32) NULL,
    ADD COLUMN profile_image_path VARCHAR(255) NULL;

UPDATE app_users SET user_code = LOWER(REPLACE(id, '-', '')) WHERE user_code IS NULL;
ALTER TABLE app_users MODIFY user_code VARCHAR(32) NOT NULL;
ALTER TABLE app_users ADD CONSTRAINT app_users_user_code_uq UNIQUE (user_code);

ALTER TABLE conversations
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'DIRECT';

ALTER TABLE messages
    ADD COLUMN message_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    ADD COLUMN attachment_path VARCHAR(255) NULL,
    ADD COLUMN attachment_name VARCHAR(255) NULL,
    ADD COLUMN attachment_content_type VARCHAR(100) NULL,
    ADD COLUMN attachment_size BIGINT NULL;
