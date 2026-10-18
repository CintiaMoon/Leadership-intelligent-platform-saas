# Runbook: Restore from backup

- Status: Stub. Not yet written or tested. Do not rely on this for a real incident.
- Owner role: DevOps lead
- Related: ADR-007, NFR-REL-003, NFR-REL-004

## Must cover
- RDS point-in-time recovery and AWS Backup snapshot restore (40-day window, RPO 15 min, RTO 4 h)
- Replaying the deletion ledger after a restore so purged companies stay purged (SPEC-DEL)
- Verification queries and who signs off
- Communication to affected users

## Test record
None yet. Record the date, the tester, and the result after the first rehearsal.
