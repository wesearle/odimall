-- Demo table for vm-edge gamma (Java). Run as DB superuser after creating odimall_vm database.
CREATE TABLE IF NOT EXISTS demo_ping (
    id   BIGSERIAL PRIMARY KEY,
    note TEXT NOT NULL
);

INSERT INTO demo_ping (note)
SELECT 'seed row for OdiMall VM edge demo'
WHERE NOT EXISTS (SELECT 1 FROM demo_ping);
