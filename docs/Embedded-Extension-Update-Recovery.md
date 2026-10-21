# Embedded extension update recovery

## Failure and ownership

The update route previously required an entry in the process-local `updateMap`.
An app restart discarded that map while installed versions, `hasUpdate`, and the
artifact ledger remained in the database. A valid update request consequently
failed before downloading its candidate. A repeated request after a successful
commit failed for the same reason.

Availability now compares the installed package with its uniquely owned durable
store entry. This reader deliberately checks only package/store ownership and
versions: APK-only legacy stores can omit a signing fingerprint and have unknown
source IDs. Authenticated direct-JAR execution still performs the complete signer,
manifest, source-ownership, artifact-integrity, and publication checks. Custom
uploads still require the explicit switch-to-store operation. An update whose
installed version is already current or newer is an idempotent no-op; the JAR
route repeats this decision while holding the installation mutex.

## Refresh and activation

Previously every successful refresh deleted a complete store partition and
reinserted it with new row IDs and tokens. Even an identical index invalidated a
candidate being downloaded. Reconciliation now preserves the exact row and token
for entries with identical advertised metadata, source metadata, and signer.
Changed or removed entries invalidate their former generation. Tokens are scoped
to individual entries; the existing journal binding still includes store URL,
signer, package, source IDs, and exact candidate bytes. No schema migration or
trust-check relaxation is required.

Refresh requests share a coroutine mutex and publish the cache timestamp after
completion. Short reconciliation, postflight-fence, and publication transactions
share a reentrant process lock before acquiring a database connection. Downloads
and source execution do not hold this lock. Database row locks and both
publication fences remain in place for cross-process safety.

A genuinely changed generation retries at most three times from a newly read,
fully authenticated candidate. Interrupted staging is discarded through the
existing owner, and publication failures use journal rollback. A failed cleanup
or rollback prevents automatic retry. Cancellation and non-stale failures are not
retried.

## Update-route errors

Only the update route returns a fixed `{code,message,retryable}` error envelope.
Messages never contain exception details, paths, SQL, or artifact URLs.

| Code | HTTP | Retryable |
| --- | --- | --- |
| `EXTENSION_NOT_FOUND` | 404 | false |
| `EXTENSION_NOT_INSTALLED` | 409 | false |
| `EXTENSION_CUSTOM_PINNED` | 409 | false |
| `EXTENSION_STORE_UNAVAILABLE` | 409 | true |
| `EXTENSION_STORE_AMBIGUOUS` | 409 | false |
| `EXTENSION_STORE_CHANGED` | 409 | true |
| `EXTENSION_DOWNLOAD_FAILED` | 502 | true |
| `EXTENSION_UPDATE_FAILED` | 500 | false |

Gateway errors are limited to classified native transport failures and explicit
connection, DNS, or socket-timeout exceptions. Unknown failures retain a safe
500 response. Retryability is a user-facing recovery hint, not permission for an
unbounded client retry loop.

## Verification

Regression tests cover unchanged and sibling-entry refresh, signer changes,
restart-equivalent loss of the update map, duplicate completed requests,
APK-only legacy availability, refreshed metadata during download, bounded stale
retries and staging cleanup, and safe error envelopes. Existing signature,
ownership, crash-recovery, and activation tests remain applicable.

The shipped 2.4.2410 artifact independently reproduced both HTTP 500 failures in
fresh temporary host data: updating immediately after restart and refreshing an
identical index during an update download. The fixture uses HTTPS and real signed
AsuraScans 1.6.66 and 1.6.69 artifacts. It does not read or mutate a personal database.

The full server suite completed with 275 tests: 265 passed, 10 existing real-corpus
fixture tests skipped, and zero failures or errors. Java 17 artifact validation
also passed. Final committed-artifact HTTP qualification is recorded separately
in the rollout evidence so changing version/build identity cannot be confused
with qualifying an earlier diagnostic JAR.
