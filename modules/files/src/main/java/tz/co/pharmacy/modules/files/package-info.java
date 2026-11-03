/**
 * Stored files and attachments: a pharmacy's logo, prescription images, supplier invoice scans.
 * Files live in an object store behind {@link
 * tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage} (local disk or S3-compatible); the
 * browser uploads and downloads directly through short-lived presigned URLs, and this module keeps
 * the record of each file per tenant.
 */
@ApplicationModule(displayName = "files")
package tz.co.pharmacy.modules.files;

import org.springframework.modulith.ApplicationModule;
