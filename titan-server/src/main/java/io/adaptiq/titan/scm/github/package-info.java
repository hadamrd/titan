/**
 * GitHub App foundation — JWT-based App authentication, installation-token exchange + caching,
 * Manifest one-click create, and the {@code titan.github_app} / {@code titan.github_installations}
 * / {@code titan.github_repositories} persistence layer (#832, design/63).
 *
 * <p>This package owns the authentication primitives and the CRUD surface only. The scanner (Child
 * B, #833), webhook handler (Child C, #834), and status reporter (Child D, #835) bind against
 * {@link io.adaptiq.titan.scm.github.GithubAppService#getInstallationToken(long)} but live in their
 * own sub-packages and ship in separate PRs.
 *
 * <p><strong>Security invariants (CONSTITUTION §6):</strong>
 *
 * <ul>
 *   <li>The App's PEM private key and webhook secret are envelope-encrypted at rest via {@link
 *       io.adaptiq.titan.credentials.EnvelopeCipher}. They are never logged, never returned by any
 *       DTO, and never serialized to JSON.
 *   <li>Installation tokens are cached in memory only (60-min TTL, refresh at 50min) and bound to a
 *       single installation id. They are never persisted.
 *   <li>{@link io.adaptiq.titan.scm.github.GithubAppWebhookSecretComparator} provides the
 *       constant-time compare primitive — Child C (#834) uses it to verify {@code
 *       X-Hub-Signature-256} on incoming webhook deliveries.
 *   <li>No URL/string sniffing: the GitHub API client takes typed parameters ({@link
 *       io.adaptiq.titan.scm.github.GithubManifestCallback}, install id, …) and constructs
 *       endpoints from those.
 * </ul>
 */
package io.adaptiq.titan.scm.github;
