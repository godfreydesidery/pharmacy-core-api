-- The account behind each entry, beside the actor's email snapshot. Entries written before keep
-- only the email: the trail is append-only and is not rewritten.
alter table audit.entries add column actor_uid uuid;
create index entries_by_actor_uid on audit.entries (actor_uid);
