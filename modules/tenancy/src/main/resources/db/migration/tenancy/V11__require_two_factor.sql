-- A pharmacy may require a second factor (an authenticator app or a passkey) of everyone who works
-- in it (gap G16, NIST SP 800-63B AAL2): members without one are sent to set it up before they can
-- work there. Off by default; the pharmacy's admin turns it on in Settings.
alter table tenancy.settings add column require_two_factor boolean not null default false;
