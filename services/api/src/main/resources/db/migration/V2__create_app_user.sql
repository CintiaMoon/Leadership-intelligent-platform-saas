-- User accounts (SPEC-AUTH, D-55, D-56). Email uniqueness is enforced on the normalized form.
CREATE TABLE app_user (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    email_normalized  text        NOT NULL,
    email_original    text        NOT NULL,
    display_name      text        NOT NULL,
    password_hash     text        NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    email_verified_at timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email_normalized UNIQUE (email_normalized),
    CONSTRAINT ck_app_user_email_lowercase CHECK (email_normalized = lower(email_normalized)),
    CONSTRAINT ck_app_user_email_length CHECK (char_length(email_normalized) BETWEEN 3 AND 254),
    CONSTRAINT ck_app_user_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 100),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
