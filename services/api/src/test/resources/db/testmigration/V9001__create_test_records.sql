-- Test-only tables (plan Q-14, D-118). They stand in for tenant-owned business records until slice 003 and later add real
-- ones, so the cross-tenant reference rule (AC-TEN-08) can be exercised as a mechanism. Never part of production.
CREATE TABLE test_record (
    id         uuid NOT NULL DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL,
    name       text NOT NULL,
    CONSTRAINT pk_test_record PRIMARY KEY (id),
    CONSTRAINT fk_test_record_company FOREIGN KEY (company_id) REFERENCES company (id),
    CONSTRAINT uq_test_record_company_id UNIQUE (company_id, id)
);

-- The composite foreign key makes a note that points at another company's record impossible in the database too.
CREATE TABLE test_note (
    id         uuid NOT NULL DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL,
    record_id  uuid NOT NULL,
    body       text NOT NULL,
    CONSTRAINT pk_test_note PRIMARY KEY (id),
    CONSTRAINT fk_test_note_record FOREIGN KEY (company_id, record_id) REFERENCES test_record (company_id, id)
);
