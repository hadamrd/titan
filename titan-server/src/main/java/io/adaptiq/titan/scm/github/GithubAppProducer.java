package io.adaptiq.titan.scm.github;

import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI wiring for the GitHub App machinery (#832, #874).
 *
 * <ul>
 *   <li>{@link GithubClientFactory} — kohsuke {@code GitHub} builder, parameterised by the GitHub
 *       REST endpoint. Defaults to {@code https://api.github.com}; rigs and tests override via
 *       {@code titan.github.api-base-url}.
 *   <li>{@link GithubAppService} — the orchestrator. Constructor-injects the factory + the {@link
 *       CredentialKeyProvider} chain (PR #816 producer wires this).
 *   <li>{@link GithubRepoScanner} — pull-based pipeline-discovery walker (#833).
 * </ul>
 */
@ApplicationScoped
public class GithubAppProducer {

  @Produces
  @ApplicationScoped
  public GithubClientFactory githubClientFactory(
      @ConfigProperty(name = "titan.github.api-base-url", defaultValue = "https://api.github.com")
          String baseUrl) {
    return new GithubClientFactory(baseUrl);
  }

  @Produces
  @ApplicationScoped
  public GithubAppService githubAppService(
      TitanStores stores, CredentialKeyProvider keyProvider, GithubClientFactory clientFactory) {
    return new GithubAppService(stores, keyProvider, clientFactory);
  }

  @Produces
  @ApplicationScoped
  public GithubRepoScanner githubRepoScanner(TitanStores stores, GithubAppService appService) {
    return new GithubRepoScanner(stores, appService);
  }
}
