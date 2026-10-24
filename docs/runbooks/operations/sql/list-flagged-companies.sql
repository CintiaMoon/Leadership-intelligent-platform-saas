-- Companies flagged for ownership resolution (D-54), oldest first, with how many ACTIVE members other than the Owner
-- they have (D-142). Read-only. A company with zero is one whose Owner was its only member: it stays flagged and retained
-- until the product and legal decide otherwise (D-40, D-100). Nobody can use it, and the reversal script still works.
SELECT c.id AS company_id, c.name, c.ownership_resolution_at,
       (SELECT count(*) FROM membership m
         WHERE m.company_id = c.id AND m.role <> 'OWNER' AND m.status = 'ACTIVE') AS other_active_members,
       (SELECT count(*) FROM membership m
         WHERE m.company_id = c.id AND m.role = 'ADMIN' AND m.status = 'ACTIVE') AS active_admins
  FROM company c
 WHERE c.ownership_resolution_at IS NOT NULL
 ORDER BY c.ownership_resolution_at, c.id;
