package io.adaptiq.titan.auth;

/**
 * Tagged target of an authorization check. In v1 the only concrete tag is {@link JobResource} —
 * both {@link Action#BUILD_RERUN} and {@link Action#PIPELINE_EDIT} take their authority from the
 * owning job (rerun's authority follows the build's {@code jobId}). The sealed shape is the seam
 * #1114 will widen with {@code FolderResource}, {@code CredentialResource}, etc.
 *
 * <p>Sealed + records is the discriminated-union typing the project prefers — string-sniffing on a
 * type field is a sev1 (see the manifesto).
 */
public sealed interface Resource permits Resource.JobResource {

  /** Stable string for audit-log details. Format: {@code "<type>:<id>"} (e.g. {@code "job:42"}). */
  String auditTag();

  /** A row in {@code titan.jobs} — the unit of authority for v1's two guarded actions. */
  record JobResource(long jobId) implements Resource {
    @Override
    public String auditTag() {
      return "job:" + jobId;
    }
  }
}
