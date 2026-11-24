-- One upload link, one upload (adversarial review of 23 Nov 2026, INT-9). A presigned PUT was
-- valid for its whole ten minutes, so whoever held the link could put other bytes under a file's key
-- after the upload, even after the file was complete and checked. The first PUT of a key now claims
-- it here, and a second PUT of the same key is refused; a PUT that fails gives its claim back, so
-- the browser may try the same link again. Shared by every instance (a row, not memory).
--
-- Platform scope, like files.pending_deletes: the PUT route has no session and no pharmacy (the
-- signature is the authorisation, and the key names the pharmacy's folder), so no tenant_uid and no
-- row-level security. A row is useless once its link has expired; later claims delete those.
create table files.upload_claims (
  storage_key text primary key,
  claimed_at  timestamptz not null,
  expires_at  timestamptz not null
);
create index upload_claims_by_expiry on files.upload_claims (expires_at);
grant select, insert, delete on files.upload_claims to ${application_role};
