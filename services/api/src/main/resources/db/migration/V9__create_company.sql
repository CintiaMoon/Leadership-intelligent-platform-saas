-- Companies, the tenant boundary (SPEC-TEN, D-105, D-106). 002 only creates ACTIVE companies; slice 007 owns PENDING_DELETION.
CREATE TABLE company (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_company PRIMARY KEY (id),
    CONSTRAINT ck_company_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_company_status CHECK (status IN ('ACTIVE', 'PENDING_DELETION'))
);
