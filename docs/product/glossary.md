# Product Glossary

| Term | Definition |
|---|---|
| User | An individual account with an authenticated identity. |
| Verified email | An email address whose ownership has been verified through the approved flow. |
| Company / Tenant | An isolated organizational boundary for company-owned data and membership. |
| Membership | The relationship between a user and a company, including role and status. |
| Owner | The role with the highest company-level authority, subject to the approved permission matrix. |
| Admin | A company role with administrative permissions as defined by the approved matrix. |
| Agent | A company role with operational permissions as defined by the approved matrix. |
| Viewer | A read-only role, subject to the approved permission matrix. |
| Invitation | A time-limited offer to join a company, bound to a normalized email address and a single-use token. |
| Ownership transfer | A workflow that transfers the Owner role only after explicit acceptance and successful invariant checks. |
| Plan | A commercial or service tier, such as Trial, Starter, or Team. |
| Entitlement | A server-evaluated permission or limit granted by a plan or subscription state. |
| Grace period | The approved interval after deletion is requested during which restoration may be allowed. |
| Purge | The permanent deletion or anonymization of data according to the approved retention policy. |
| Tombstone | A minimal retained record indicating a deletion event without retaining unnecessary personal or company data. |
| ADR | Architecture Decision Record documenting a significant technical decision and its rationale. |
| Outbox | A durable record of work to be delivered after a database transaction commits. |
