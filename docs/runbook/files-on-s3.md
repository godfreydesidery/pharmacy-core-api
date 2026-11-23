# Files on S3

Pharmacy Core keeps files (prescription scans, supplier invoices, logos, data-export ZIPs) in an
object store behind the files module's `ObjectStorage` port. The two stores are:

- **`local`**: a directory on a volume. Use it for development and for a single instance only.
- **`s3`**: an S3-compatible bucket, such as AWS S3, MinIO or Cloudflare R2. Use it in production
  and whenever several API instances run.

Backups and restores of the bucket are in [backup-restore.md](backup-restore.md).

## How files move

| Step | Path | Notes |
|---|---|---|
| Upload | Browser → `PUT /api/v1/files/blob/{key}?exp&ct&sig` → API → store | Always through the API, whatever the store. The API checks the HMAC signature, the type and the size (more than 10 MB is refused with a 413, before or while reading). It then streams the bytes on. Up to 16 MiB of known size goes as one PutObject. Anything larger or of unknown size goes as a multipart upload in 8 MiB parts, so at most one part is in memory. A failed multipart upload is aborted. |
| Complete | `POST /files/uploads/{uid}/complete` | The API reads the object back, records its size and SHA-256 and makes the file visible. |
| Download | `GET /files/{uid}/url` → presigned GET of the bucket | The link lasts 5 minutes for files and 15 minutes (`PHARMACY_EXPORTS_LINK_TTL`) for exports. The link sets `Content-Type`, `Content-Disposition` (`inline` for files, `attachment` for export ZIPs, with the file's name) and `Cache-Control: private`. With `local` the link is the API's own signed blob route instead. |
| Data export | The worker writes the ZIP to the same store | It uses the same streaming and multipart path, under `tenants/{tenant}/exports/{file}/{name}`. |
| Delete | The record is marked deleted, and the object is deleted after the commit | If the store refuses, the delete is queued in `files.pending_deletes` and retried by the nightly retention (FOR UPDATE SKIP LOCKED, one instance per row). |

The keys are the same in both stores: `tenants/{tenant}/{yyyy}/{mm}/{file}` and the export
layout above. An optional prefix is put in front of them in the bucket.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PHARMACY_FILES_STORAGE` | `local` | `local` or `s3` |
| `PHARMACY_FILES_SIGNING_SECRET` | dev value | **Required with both stores.** At least 32 random characters. It signs the upload links (and the local store's download links). The API refuses to start without it outside `local`/`test`. |
| `PHARMACY_FILES_ROOT` | `target/files` | `local` only: an absolute path on a volume |
| `PHARMACY_FILES_S3_BUCKET` | — | **Required with `s3`** (checked at start) |
| `PHARMACY_FILES_S3_REGION` | `AWS_REGION` | `af-south-1` in production |
| `PHARMACY_FILES_S3_ENDPOINT` | — | MinIO, R2 or similar: `http://localhost:9000`, `https://<account>.r2.cloudflarestorage.com`. Leave it empty for AWS. |
| `PHARMACY_FILES_S3_PATH_STYLE` | on with an endpoint | `bucket` in the path instead of the host name |
| `PHARMACY_FILES_S3_PREFIX` | — | For example `files/`. Lets the bucket hold other things too. |
| `PHARMACY_FILES_S3_SSE` | `AES256` | `AES256` (SSE-S3) or `aws:kms` (SSE-KMS). `none` is for MinIO without a KMS or for R2. |
| `PHARMACY_FILES_S3_KMS_KEY_ID` | — | The KMS key id, ARN or alias. Setting it makes SSE-KMS the default. |

**There are no access keys in the configuration.** Credentials come from the AWS SDK's default
provider chain:

- in production on ECS: the **task role**;
- on a laptop or for MinIO: `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` or `AWS_PROFILE`.

The old `PHARMACY_FILES_S3_ACCESS_KEY` / `_SECRET_KEY` are gone.

**The web app** must allow the bucket in its Content-Security-Policy, or images (the logo,
thumbnails) are blocked. Set `FILES_ORIGIN` on the web service:

- AWS: `https://<bucket>.s3.af-south-1.amazonaws.com`;
- MinIO or R2: the endpoint's origin.

### Local development with MinIO

```bash
docker run -d -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio-secret \
  <a MinIO image you trust> server /data --console-address :9001
# create the bucket pharmacy-files in the console (http://localhost:9001), then:
export PHARMACY_FILES_STORAGE=s3 PHARMACY_FILES_S3_BUCKET=pharmacy-files \
       PHARMACY_FILES_S3_ENDPOINT=http://localhost:9000 PHARMACY_FILES_S3_SSE=none \
       AWS_ACCESS_KEY_ID=minio AWS_SECRET_ACCESS_KEY=minio-secret
```

MinIO no longer publishes community images on Docker Hub, so pick an image you trust.
`adobe/s3mock` also works for quick tests (no signature checks).

## What AWS must provide

These belong in the Terraform.

### Bucket (`af-south-1`, one per environment)

- **Block Public Access**: all four settings on. **Object Ownership**: bucket owner enforced (ACLs
  off).
- **Default encryption**: SSE-KMS with a customer-managed key and **S3 Bucket Key enabled**, or
  SSE-S3. The API also asks for encryption on every write.
- **Versioning**: enabled. Restoring a deleted or overwritten file depends on it.
- **Lifecycle rules**:
  1. Expire noncurrent versions after **35 days**, the same window as the database's
     point-in-time recovery.
  2. Remove expired object delete markers.
  3. Abort incomplete multipart uploads after **1 day**.
- **Bucket policy**: deny any request with `aws:SecureTransport = false`. Optionally, deny all
  principals except the API's task role, the copy and the operators' break-glass role.
- **No CORS rule is needed.** Uploads go through the API, and downloads are navigations or
  `<img>` loads.
- Optional: CloudTrail S3 data events or server access logs for an audit of reads.
- An **S3 gateway VPC endpoint** for the ECS subnets, so the API reaches S3 without the NAT.

### IAM: the API's ECS task role

Scope it to `arn:aws:s3:::<bucket>/<prefix>*`:

```json
{ "Effect": "Allow",
  "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"],
  "Resource": "arn:aws:s3:::<bucket>/<prefix>*" },
{ "Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::<bucket>",
  "Condition": { "StringLike": { "s3:prefix": "<prefix>*" } } }
```

- Without `s3:ListBucket`, S3 answers 403 instead of 404 for a missing object, so "is it there?"
  fails instead of answering no.
- The presigned GETs are signed with the task role's credentials. A download therefore needs the
  same `s3:GetObject` (and `kms:Decrypt`), and the browser needs no credentials of its own.

### KMS (with SSE-KMS)

- The key policy lets the task role use `kms:GenerateDataKey` and `kms:Decrypt`.
- The copy's role and the operators' restore role need the same.

### Region

- `af-south-1` is an opt-in region: enable it in the account first.

## Moving the files from a volume to S3 (cut-over)

The copy is a mode of the API itself. Started with `--pharmacy.files.copy-local-to-s3=true`, it
does the following:

1. It copies every file under `PHARMACY_FILES_ROOT` to the bucket under the same key. It leaves
   unfinished `.part` files out. It records each object's SHA-256 in its `sha256` metadata.
2. It checks every object after the copy: size and SHA-256 metadata. With
   `--pharmacy.files.copy-verify=full` it also reads every object back and hashes it. Both are
   command-line flags only: an environment variable left set would make the API copy and exit on
   every restart.
3. It logs a report: files seen, copied, already there, vanished, failed. Then it exits: status 0
   when nothing failed, 1 otherwise.

It is **idempotent and resumable**. A file already in S3 with the same size and SHA-256 is skipped.
Anything else (missing, different, half-copied by a run that died) is copied again. Run it as often
as needed. It reads the `PHARMACY_FILES_S3_*` settings whatever `PHARMACY_FILES_STORAGE` says. It
needs the volume mounted, the database settings (it starts the application context) and AWS
credentials.

```bash
# the one-off command (same image, same environment as the API, the volume mounted)
PHARMACY_DB_MIGRATIONS_ENABLED=false JAVA_TOOL_OPTIONS="-Xmx384m" \
  java org.springframework.boot.loader.launch.JarLauncher \
  --pharmacy.files.copy-local-to-s3=true --spring.main.web-application-type=none
```

On Railway, run it inside the API's own container (`railway ssh`), which is the one with the
volume. Give the second JVM its own `-Xmx`, because the container's `JAVA_TOOL_OPTIONS` assumes it
is alone. Railway has no task role, so use a dedicated IAM user for the move:

- give it only the bucket permissions above;
- pass its keys as `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`;
- delete it after the move.

### Order

0. **Prepare.**
   - The bucket, the IAM, `FILES_ORIGIN` on the web and the `PHARMACY_FILES_S3_*` variables are set
     on the API, with `PHARMACY_FILES_STORAGE` still `local`.
   - Follow the usual deploy safety routine before each redeploy: the migration safety test, a
     start on the QA copy, the row counts.
1. **Copy** while the API keeps running. The report must say `0 failed`.
2. **Switch.** Set `PHARMACY_FILES_STORAGE=s3` and redeploy. **Keep the volume mounted.**
   - From now on, uploads go to S3 and downloads come from S3.
   - A file uploaded between step 1 and the switch exists only on the volume until step 3. Do
     steps 2 and 3 back to back, in a quiet hour.
3. **Copy again** (the same command). It copies only what was written meanwhile and skips the rest.
4. **Verify.**
   - The report says `0 failed`.
   - `FILES_S3_URI=s3://<bucket>/<prefix> ops/files/check-records.sh`, run as the backup role,
     reports `OK`: every file the database lists as complete is in the bucket with its size.
   - Open a few attachments, the logo and an export in the web app.
5. **Afterwards.** Keep the volume, unused, for 35 days as the way back, then delete it.

**Rollback** (before step 5):

1. Copy the bucket back onto the volume: `aws s3 sync s3://<bucket>/<prefix> /data/files`.
   Uploads made since the switch exist only in S3.
2. Set `PHARMACY_FILES_STORAGE=local`.
3. Redeploy.

A file deleted during the cut-over can stay behind in S3 as an orphan. That is harmless; it has no
record.

## Tests

| Test | What it covers |
|---|---|
| `FilesS3StorageTest` | One contract for every store, run against three: the local directory, an in-process S3 fake (`FakeS3`: the JDK's HTTP server speaking enough of the S3 REST API; always runs, offline) and Adobe's S3Mock in a container when Docker is running (`adobe/s3mock:4.7.0`, pulled once). The S3 runs use the real SDK client with a custom endpoint and path-style requests. The contract covers: round trips; unknown sizes; 20 MiB and 17 MiB multipart uploads; replace and delete; missing objects (404); presigned GET with type and file name; the prefix; the copy tool (idempotent, resumable, checked); SSE-S3, SSE-KMS and none; aborting a failed multipart upload. |
| `FilesOnS3Test` | The whole API with `storage=s3` against `FakeS3`: the browser's begin, PUT, complete flow through the signed route (the 413 cap, SSE on the object, the SHA-256); the presigned download with the file's name; a multipart form upload; a delete that removes the object. |
| `UrlSignerTest` | The signed blob URLs, the signed Content-Disposition, upload URLs whatever the store |

The Testcontainers MinIO module was not used: MinIO's community images are no longer on Docker
Hub. The in-process fake keeps the S3 path covered when Docker is not running.
