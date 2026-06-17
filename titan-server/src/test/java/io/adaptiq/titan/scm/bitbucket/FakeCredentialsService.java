package io.adaptiq.titan.scm.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic in-memory {@link CredentialsService} fake for the Bitbucket adapter tests. Only
 * {@link #resolvePlaintext} is exercised; every other method throws so a test that accidentally
 * relies on un-faked behaviour fails loudly rather than silently passing (Titan testing manifesto:
 * Protocol/Fake parity, no silent drift).
 */
final class FakeCredentialsService implements CredentialsService {

  private final Map<String, String> store = new HashMap<>();

  void put(@NonNull String scope, @NonNull String key, @NonNull String value) {
    store.put(scope + "/" + key, value);
  }

  @Override
  @NonNull
  public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
    return Optional.ofNullable(store.get(scope + "/" + key));
  }

  @Override
  @NonNull
  public Optional<Credential> findById(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public List<Credential> listAll() {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public List<Credential> listByScope(@NonNull String scope) {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public Credential create(@NonNull NewCredentialRequest request) {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public Credential update(long id, @NonNull CredentialUpdate update) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void delete(long id) {
    throw new UnsupportedOperationException();
  }

  @Override
  @NonNull
  public String backendName() {
    return "fake-bitbucket-test";
  }

  @Override
  public int rotateKek() {
    return 0;
  }
}
