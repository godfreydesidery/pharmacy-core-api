# Rotating keys

Pharmacy Core seals the secrets it must read back with AES-256-GCM:

- TOTP seeds of authenticator apps (`access.users.totp_secret`), and live-stream tickets;
- message provider secrets (`notifications.provider_settings.secrets`);
- webhook signing secrets (`integrations.webhooks.secret`, `previous_secret`).

Since OPS-5 (adversarial review, 23 Nov 2026) they are sealed under named keys in a keyring. A key can be
rotated without making any stored value unreadable, and each purpose has its own key.

## How it works

| Setting | Variable | Meaning |
|---|---|---|
| `pharmacy.keys.<id>` | `PHARMACY_KEYS_<ID>` | A named key: at least 32 random characters. The id is 1 to 32 lower-case letters and digits and starts with a letter, for example `k2026t`. |
| `pharmacy.access.encryption-key` | `PHARMACY_ACCESS_ENCRYPTIONKEY` | The **legacy** key (id `legacy`). It sealed everything before key ids existed. |
| `pharmacy.sealing.totp` | `PHARMACY_SEALING_TOTP` | Id of the key that seals new TOTP seeds. Unset: `legacy`. |
| `pharmacy.sealing.providers` | `PHARMACY_SEALING_PROVIDERS` | Id of the key that seals new provider secrets. Unset: `legacy`. |
| `pharmacy.sealing.webhooks` | `PHARMACY_SEALING_WEBHOOKS` | Id of the key that seals new webhook secrets. Unset: `legacy`. |

- **Value format.** A value sealed under a named key reads `v2:<keyId>:<base64 IV + ciphertext>`. Its purpose is
  bound as AES-GCM associated data, so a TOTP value never opens as a provider secret. Values sealed before keys
  had ids, and values sealed while a purpose is still on `legacy`, keep the old unversioned format. The previous
  release can therefore read them during a rolling deploy.
- **Opening values.** Every key in the keyring opens the values that name it, whatever purpose is active. Changing
  a purpose's active key never breaks a stored value.
- **Why separate keys, not HKDF.** Separate configured keys are used rather than purpose keys derived from one
  master (HKDF). Each key can be rotated, or revoked after a leak, on its own, and each one is a single ECS secret.
- **Refused at start.** The API refuses to start, outside `local` and `test`, in these cases:
  - a key is shorter than 32 characters;
  - a key is the published development key;
  - two keys share the same value;
  - an active id names a key that is not configured;
  - a purpose still falls back to `legacy` and `PHARMACY_ACCESS_ENCRYPTIONKEY` is unset.
- **Key material.** Each key's AES key is the SHA-256 of its text, as the legacy key always was. Generate keys
  with `openssl rand -base64 32` (44 characters).

### Where the keys live on AWS

Keep one Secrets Manager secret per key (simplest to delete later), or one JSON secret holding several keys. Either
way, map each key to its own environment variable in the **API service's** task definition:

```json
"secrets": [
  { "name": "PHARMACY_ACCESS_ENCRYPTIONKEY", "valueFrom": "arn:aws:secretsmanager:af-south-1:<acct>:secret:pharmacy/prod/key-legacy-AbCdEf" },
  { "name": "PHARMACY_KEYS_K2026T", "valueFrom": "arn:aws:secretsmanager:af-south-1:<acct>:secret:pharmacy/prod/keys-XyZ123:k2026t::" }
],
"environment": [
  { "name": "PHARMACY_SEALING_TOTP", "value": "k2026t" }
]
```

The active key ids are not secret; they are plain `environment` entries. The migrate-only task needs no key at all.

## Step by step

Do every step as a normal deploy: a new task definition revision, then a rolling service update. **Never** add a
key and make it active in the same deploy. While the deploy rolls, instances without the key would meet values
sealed under it.

> Before the first rotation, run this release (or a later one) everywhere with no key change. A value in the `v2`
> format can only be read by a release that has the keyring.

1. **Make the new keys.** Use one key per purpose:

   ```bash
   openssl rand -base64 32   # k2026t (totp)
   openssl rand -base64 32   # k2026p (providers)
   openssl rand -base64 32   # k2026w (webhooks)
   ```

   Store them in Secrets Manager, for example `pharmacy/prod/keys` holding `{"k2026t":"…","k2026p":"…","k2026w":"…"}`.
   The API service's execution role must be allowed `secretsmanager:GetSecretValue` on the secret.

2. **Add the keys; activate none yet.** Add `PHARMACY_KEYS_K2026T`, `PHARMACY_KEYS_K2026P` and
   `PHARMACY_KEYS_K2026W` to the API task definition. Deploy. The start-up log shows
   `Keyring: keys [legacy, k2026p, k2026t, k2026w], active {TOTP=legacy, …}`. Every instance can now open values
   under the new keys.

3. **Activate the keys.** Add `PHARMACY_SEALING_TOTP=k2026t`, `PHARMACY_SEALING_PROVIDERS=k2026p` and
   `PHARMACY_SEALING_WEBHOOKS=k2026w`. Deploy. New values are now sealed with them. You can activate one purpose
   at a time.

4. **Re-seal the old values.** As a root administrator:

   ```bash
   curl -X POST https://api.<host>/api/v1/platform/keys/reseal -H "Authorization: Bearer $ROOT_TOKEN"
   # 202 "Re-seal started"; 409 reseal_running if it already runs on some instance
   ```

   - **Where it runs.** On one instance at a time, under a PostgreSQL advisory lock. It reads the platform's rows,
     then every pharmacy's (active, suspended or closed), in batches of 200 values per transaction
     (`pharmacy.sealing.reseal-batch`).
   - **Progress.** The log shows `Key re-seal: <column> (<tenant>) N values so far`, then
     `Key re-seal completed: N values re-sealed, M unreadable`.

5. **Check the counts.**

   ```bash
   curl https://api.<host>/api/v1/platform/keys -H "Authorization: Bearer $ROOT_TOKEN"
   ```

   - `columns[].byKey`: how many values name each key id, per column (`plain`: a webhook secret saved before
     sealing).
   - `keys[]`: each key with `values` and `removable`.
   - `toReseal`: values not yet under their purpose's active key.
   - `unknownKeys`: values that name a key the keyring does not hold. They cannot be opened; find out why before
     going on.
   - `lastRun`: this instance's last run only. The counts cover the whole database.

   Run the re-seal again if `toReseal` is not 0. A row that a sign-in or an admin held at that moment is skipped
   and taken on the next run.

6. **Remove the old key.** Wait until `GET` shows the old key with `"values": 0` and `"removable": true`. Then
   remove its variable from the task definition and deploy.
   - **For `legacy`.** Remove `PHARMACY_ACCESS_ENCRYPTIONKEY` only once all three purposes have their own key. The
     API refuses to start without it while any purpose still falls back to it.
   - **Check again just before.** Run `GET` again just before you deploy, and re-seal again if a value appeared
     (for example, one written by an instance that was still on the old configuration).
   - **Delete the secret.** Schedule its deletion in Secrets Manager with a recovery window (7 to 30 days).
     Restoring it within the window is the way back if a value was missed.

### After a leak

If a key leaked together with a copy of the database, rotating the key does not protect the secrets already
exposed. Rotate as above, and also replace the secrets themselves:
- the people with authenticator apps set them up again;
- the provider API keys and passwords are changed at the providers;
- the webhook secrets are rotated (`POST /api/v1/webhooks/{uid}/rotate-secret`).

### Railway (test environment)

The same variables apply. Railway has no secret store, so set the keys as service variables.
