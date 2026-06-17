package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Thin delegate over a {@link SecretsBackend} — the public service callers see.
 *
 * <p>All persistence + crypto lives in the backend; this class is purely a CDI-friendly surface
 * that lets the rest of the engine depend on a stable type while the backend is plug-replaced at
 * runtime (db-envelope by default; Vault / AWS / GCP via ServiceLoader-discovered alternatives).
 *
 * <p>Constructor injection only. The {@link SecretsBackend} bean is produced by {@link
 * io.adaptiq.titan.boot.SecretsBackendProducer}.
 */
@ApplicationScoped
public class CredentialsServiceImpl implements CredentialsService {

  private final SecretsBackend backend;

  public CredentialsServiceImpl(SecretsBackend backend) {
    this.backend = backend;
  }

  @Override
  @NonNull
  public Optional<Credential> findById(long id) {
    return backend.findById(id);
  }

  @Override
  @NonNull
  public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
    return backend.findByScopeAndKey(scope, key);
  }

  @Override
  @NonNull
  public List<Credential> listAll() {
    return backend.listAll();
  }

  @Override
  @NonNull
  public List<Credential> listByScope(@NonNull String scope) {
    return backend.listByScope(scope);
  }

  @Override
  @NonNull
  public Credential create(@NonNull NewCredentialRequest request) {
    return backend.create(request);
  }

  @Override
  @NonNull
  public Credential update(long id, @NonNull CredentialUpdate update) {
    return backend.update(id, update);
  }

  @Override
  public void delete(long id) {
    backend.delete(id);
  }

  @Override
  @NonNull
  public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
    return backend.resolvePlaintext(scope, key);
  }

  /** Operator-facing handle for a KEK rotation pass. Returns the number of rows re-wrapped. */
  public int rotateKek() {
    return backend.rotateKek();
  }

  /** Diagnostic — which backend is bound. */
  @NonNull
  public String backendName() {
    return backend.name();
  }
}
