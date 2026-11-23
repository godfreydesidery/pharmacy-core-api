-- What a session proved, and when (NIST SP 800-63B reauthentication; OWASP ASVS V6/V7).
-- verified_at: when its password (or passkey) was last proved, at sign-in or by confirming the
-- password again. A sensitive change to the account (passkeys, two-factor, phone) needs it within
-- a few minutes, so an unattended or stolen session cannot make one.
-- second_factor: it proved a second factor (an authenticator code, a recovery code, or a passkey
-- with user verification). A pharmacy that requires two-factor now checks the session, not merely
-- that the account has a factor set up.
-- Sessions has no row-level security (it is platform scope), so the backfill sees every row.
alter table access.sessions
  add column verified_at timestamptz,
  add column second_factor boolean not null default false;

update access.sessions
   set verified_at = created_at,
       second_factor = coalesce(method in ('TWO_FACTOR', 'PASSKEY'), false);
