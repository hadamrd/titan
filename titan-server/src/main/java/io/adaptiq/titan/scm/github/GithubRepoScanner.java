package io.adaptiq.titan.scm.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.ParameterModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.TriggerModel;
import io.adaptiq.titan.flow.parser.TitanYamlParser;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import io.adaptiq.titan.store.rows.GithubRepositoryRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.HttpException;

/**
 * Walks each installation's repos and discovers {@code .titan/pipelines/*.yml} pipeline files —
 * Child B of epic #831 (design/63 §3). Reworked in #874 to delegate every GitHub round-trip to the
 * {@code org.kohsuke:github-api} library — see {@link GithubAppService#installationClient(long)}.
 *
 * <h2>Scan loop</h2>
 *
 * <p>For each non-suspended {@link GithubInstallationRow}:
 *
 * <ol>
 *   <li>Sync the repo list via {@link GithubAppService#syncRepositoriesForInstall} — keeps {@code
 *       titan.github_repositories} fresh.
 *   <li>For each repo, fetch the {@code .titan/pipelines} directory via {@link
 *       GHRepository#getDirectoryContent(String)}. A {@link GHFileNotFoundException} (the
 *       "directory doesn't exist" signal) is treated as empty — we stamp {@code last_scanned_at}
 *       and move on.
 *   <li>For each {@code .yml}/{@code .yaml} child file, fetch its content via {@link
 *       GHContent#read()}, run {@link TitanYamlParser#parseAndValidate(String)}, and upsert a
 *       {@code titan.github_pipelines_discovered} row.
 *   <li>A parser exception lands the row with {@code parse_error} set and {@code parsed_metadata}
 *       null — the UI surfaces it as a broken pipeline.
 *   <li>A 401 (revoked / uninstalled) suspends the installation and aborts that install's scan
 *       cleanly; siblings continue.
 * </ol>
 */
public class GithubRepoScanner {

  private static final Logger LOGGER = Logger.getLogger(GithubRepoScanner.class.getName());

  /** The literal path the scanner inspects in every repo. Single source of truth. */
  static final String PIPELINES_DIR = ".titan/pipelines";

  /** Jackson mapper for the {@code parsed_metadata} JSON projection. */
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  private final TitanStores stores;
  private final GithubAppService appService;

  public GithubRepoScanner(@NonNull TitanStores stores, @NonNull GithubAppService appService) {
    this.stores = stores;
    this.appService = appService;
  }

  /** Aggregate metrics returned from a {@link #scanAll()} or {@link #scanInstall(long)} call. */
  public record ScanReport(
      int installsScanned, int reposScanned, int pipelinesFound, int suspendedInstalls) {

    static ScanReport empty() {
      return new ScanReport(0, 0, 0, 0);
    }

    ScanReport mergeWith(@NonNull ScanReport other) {
      return new ScanReport(
          this.installsScanned + other.installsScanned,
          this.reposScanned + other.reposScanned,
          this.pipelinesFound + other.pipelinesFound,
          this.suspendedInstalls + other.suspendedInstalls);
    }
  }

  /** Scan every non-suspended installation. Failures on one install are isolated. */
  @NonNull
  public ScanReport scanAll() {
    ScanReport agg = ScanReport.empty();
    List<GithubInstallationRow> installs = stores.githubInstallations().listAll();
    for (GithubInstallationRow install : installs) {
      if (install.suspendedAt != null) {
        continue;
      }
      try {
        agg = agg.mergeWith(scanInstall(install.installId));
      } catch (GithubApiException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan-github] scan failed for install {0}: {1}",
            new Object[] {install.installId, e.getMessage()});
      }
    }
    return agg;
  }

  /**
   * Scan one installation: sync its repo list, then walk each repo's {@code .titan/pipelines/}
   * against the repo's default branch. A 401 from the install-token suspends the installation and
   * re-throws so the caller (REST endpoint / webhook handler) can surface the failure.
   *
   * <p>The scheduled-fallback path stays default-branch-only on purpose (#887 / design 65) — a
   * per-branch crawl would explode the API quota. Per-branch parses live on the push-webhook path
   * via {@link #scanSingleRepo(long, long, String)}.
   */
  @NonNull
  public ScanReport scanInstall(long installId) {
    GitHub installClient;
    try {
      installClient = appService.installationClient(installId);
    } catch (GithubApiException e) {
      handleAuthFailure(installId, e);
      throw e;
    } catch (IOException e) {
      throw new GithubApiException("install client build failed: " + e.getMessage(), -1, e);
    }

    try {
      appService.syncRepositoriesForInstall(installId);
    } catch (GithubApiException e) {
      handleAuthFailure(installId, e);
      throw e;
    }

    int reposScanned = 0;
    int pipelinesFound = 0;
    List<GithubRepositoryRow> repos = stores.githubRepositories().listByInstall(installId);
    for (GithubRepositoryRow repo : repos) {
      try {
        // Scheduled fallback path: only ever the default branch. Per-design 65, a per-branch
        // crawl across every install on every tick would blow the GitHub API quota.
        String branch = repo.defaultBranch != null ? repo.defaultBranch : "main";
        int found = scanRepo(installClient, repo, branch);
        pipelinesFound += found;
        reposScanned++;
      } catch (GithubApiException e) {
        if (e.status() == 401) {
          handleAuthFailure(installId, e);
          throw e;
        }
        LOGGER.log(
            Level.WARNING,
            "[titan-github] repo scan failed for {0}/{1}: HTTP {2}",
            new Object[] {repo.owner, repo.name, e.status()});
      }
    }
    return new ScanReport(1, reposScanned, pipelinesFound, 0);
  }

  /**
   * Scan exactly one repo of one installation, re-parsing its {@code .titan/pipelines/} directory
   * and upserting the {@code github_pipelines_discovered} rows. This is the event-driven entry
   * point invoked from the push-webhook handler (issue #886 / design 65 §"event-driven re-parse"):
   * when a push arrives, we re-evaluate that one repo synchronously instead of waiting for the
   * scheduled fallback tick.
   *
   * <p>Returns {@code 0} if the install or repo is unknown — the caller (webhook handler) treats an
   * unknown repo as a no-op for discovery and continues with build dispatch. A 401 propagates the
   * same way as {@link #scanInstall(long)} (suspends install, re-throws); any other API failure
   * propagates as a {@link GithubApiException} for the caller to log + swallow.
   */
  public int scanSingleRepo(long installId, long repoId, @NonNull String branch) {
    Optional<GithubRepositoryRow> repoOpt =
        stores.githubRepositories().listByInstall(installId).stream()
            .filter(r -> r.repoId == repoId)
            .findFirst();
    if (repoOpt.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[titan-github] scanSingleRepo: repo {0} not registered for install {1} — skipping",
          new Object[] {repoId, installId});
      return 0;
    }

    GitHub installClient;
    try {
      installClient = appService.installationClient(installId);
    } catch (GithubApiException e) {
      handleAuthFailure(installId, e);
      throw e;
    } catch (IOException e) {
      throw new GithubApiException("install client build failed: " + e.getMessage(), -1, e);
    }

    try {
      return scanRepo(installClient, repoOpt.get(), branch);
    } catch (GithubApiException e) {
      if (e.status() == 401) {
        handleAuthFailure(installId, e);
      }
      throw e;
    }
  }

  /**
   * Back-compat overload — defaults to the repo's recorded default branch, falling back to {@code
   * "main"} if unknown. Kept for callers (and tests) that pre-date the branch-aware refactor.
   */
  public int scanSingleRepo(long installId, long repoId) {
    Optional<GithubRepositoryRow> repoOpt =
        stores.githubRepositories().listByInstall(installId).stream()
            .filter(r -> r.repoId == repoId)
            .findFirst();
    String branch =
        repoOpt.map(r -> r.defaultBranch != null ? r.defaultBranch : "main").orElse("main");
    return scanSingleRepo(installId, repoId, branch);
  }

  // ── per-repo work ─────────────────────────────────────────────────────────

  private int scanRepo(
      @NonNull GitHub installClient, @NonNull GithubRepositoryRow repo, @NonNull String branch)
      throws GithubApiException {
    GHRepository ghRepo;
    try {
      ghRepo = installClient.getRepository(repo.owner + "/" + repo.name);
    } catch (GHFileNotFoundException e) {
      // Repo gone (deleted, archived-out-of-scope) — log + mark scanned to clear staleness.
      stores.githubRepositories().markScanned(repo.repoId);
      return 0;
    } catch (HttpException e) {
      throw httpFail("get-repository " + repo.owner + "/" + repo.name, e);
    } catch (IOException e) {
      throw ioFail("get-repository " + repo.owner + "/" + repo.name, e);
    }

    List<GHContent> entries;
    try {
      // Branch-aware listing (#887, design 65) — kohsuke's two-arg overload accepts a ref so we
      // see the push branch's HEAD tree, not the default-branch tree.
      entries = ghRepo.getDirectoryContent(PIPELINES_DIR, branch);
    } catch (GHFileNotFoundException e) {
      // No `.titan/pipelines` directory on this branch — wipe just THIS branch's rows; rows on
      // other branches (including the canonical default-branch row the UI shows) are untouched.
      stores.githubPipelinesDiscovered().deleteByRepoAndBranch(repo.repoId, branch);
      // Design 66: when the pipelines directory disappears from the DEFAULT branch, the derived
      // jobs become orphans — delete them so dispatch doesn't fire phantom builds. Feature branches
      // don't drive job rows.
      if (isDefaultBranch(repo, branch)) {
        syncJobsToDiscovery(repo, List.of());
      }
      stores.githubRepositories().markScanned(repo.repoId);
      return 0;
    } catch (HttpException e) {
      throw httpFail("list-directory-contents " + repo.owner + "/" + repo.name + "@" + branch, e);
    } catch (IOException e) {
      throw ioFail("list-directory-contents " + repo.owner + "/" + repo.name + "@" + branch, e);
    }

    // Stage the per-file rows in-memory; only after every file has been fetched + parsed do we
    // delete + reinsert. That keeps the row set consistent on per-repo HTTP failures.
    List<DiscoveredFile> discovered = new ArrayList<>();
    for (GHContent entry : entries) {
      if (!entry.isFile()) {
        continue;
      }
      String name = entry.getName();
      if (!isYamlFile(name)) {
        continue;
      }
      String content;
      String sha;
      try {
        sha = entry.getSha();
        // getContent() returns the base64-decoded UTF-8 text — kohsuke handles the encoding
        // wrapping internally (formerly the 60-char chunked-base64 hand-decode in
        // GithubApiHttpClient#getFileContent).
        content = readContent(entry);
      } catch (GHFileNotFoundException e) {
        continue;
      } catch (HttpException e) {
        throw httpFail("get-file-content " + entry.getPath(), e);
      } catch (IOException e) {
        throw ioFail("get-file-content " + entry.getPath(), e);
      }
      discovered.add(parseOne(name, sha, content));
    }

    // Branch-scoped flush (#887): only purge this branch's rows. Other branches' rows are
    // independent records of what that branch's HEAD tree had at last parse.
    stores.githubPipelinesDiscovered().deleteByRepoAndBranch(repo.repoId, branch);
    for (DiscoveredFile df : discovered) {
      stores
          .githubPipelinesDiscovered()
          .insert(
              repo.repoId, branch, df.filename, df.contentSha, df.parsedMetadata, df.parseError);
    }

    // Design 66: discovered pipelines on the DEFAULT branch ARE jobs — no manual enable step. We
    // reconcile titan.jobs against the freshly-parsed default-branch set: successfully-parsed
    // files become job rows (insert or update); files that disappeared have their job removed;
    // parse failures do NOT create jobs (a malformed YAML must not auto-enable a half-baked
    // pipeline that would then fire on every push). Feature-branch scans never touch jobs — they
    // exist purely to feed the build-dispatch path with that branch's parse.
    if (isDefaultBranch(repo, branch)) {
      syncJobsToDiscovery(repo, discovered);
    }
    stores.githubRepositories().markScanned(repo.repoId);
    return discovered.size();
  }

  private static boolean isDefaultBranch(
      @NonNull GithubRepositoryRow repo, @NonNull String branch) {
    String def = repo.defaultBranch != null ? repo.defaultBranch : "main";
    return def.equals(branch);
  }

  /**
   * Reconcile {@code titan.jobs} against the just-parsed default-branch pipeline set for one repo
   * (design 66). Idempotent: rerunning with the same input set produces no DB writes beyond the
   * UPDATE timestamps. The reconciliation rule is "the file IS the pipeline":
   *
   * <ul>
   *   <li>Each successfully-parsed file (parse_error == null) maps to exactly one job row keyed by
   *       {@code full_name = "<owner>/<name>/<shortName>"}. INSERT if missing, UPDATE
   *       pipeline_script + display_name if present.
   *   <li>Any existing job row for this (installId, repoId) whose shortName is NOT in the
   *       just-discovered set has its file deleted upstream — we delete the row. Hard delete;
   *       design 66 explicitly chooses "delete file = delete pipeline" over a soft-disable bit.
   *   <li>Parse failures contribute nothing — no job row is created or removed for a broken YAML.
   * </ul>
   */
  private void syncJobsToDiscovery(
      @NonNull GithubRepositoryRow repo, @NonNull List<DiscoveredFile> discovered) {
    long installId = repo.installId;
    long repoId = repo.repoId;
    List<JobRow> existing = stores.jobs().listAllByGithubRepo(installId, repoId);
    java.util.Map<String, JobRow> existingByFullName = new java.util.HashMap<>();
    for (JobRow j : existing) {
      if (j.fullName != null) {
        existingByFullName.put(j.fullName, j);
      }
    }
    java.util.Set<String> keepFullNames = new java.util.HashSet<>();
    for (DiscoveredFile df : discovered) {
      if (df.parseError != null) {
        continue; // never auto-enable a malformed pipeline
      }
      String shortName = stripExtension(df.filename);
      String fullName = repo.owner + "/" + repo.name + "/" + shortName;
      keepFullNames.add(fullName);
      // config_json mirrors the discovered filename so the manual-Enable consumers that
      // substring-match on `configJson.contains(filename)` keep working without churn.
      String configJson =
          "{\"source\":\"github-app\",\"filename\":\""
              + jsonEscape(df.filename)
              + "\",\"branch\":\""
              + jsonEscape(repo.defaultBranch != null ? repo.defaultBranch : "main")
              + "\"}";
      // The pipeline_script we persist is the raw YAML content the scanner just parsed; the
      // discovered row already validated it, so we trust the bytes.
      String yaml = df.rawYaml != null ? df.rawYaml : "";
      JobRow match = existingByFullName.get(fullName);
      if (match == null) {
        JobRow row = new JobRow();
        row.fullName = fullName;
        row.displayName = shortName;
        row.folderPath = repo.owner + "/" + repo.name;
        row.pipelineScript = yaml;
        row.configJson = configJson;
        row.createdBy = "github-app:discovery";
        row.enabled = true;
        row.githubInstallationId = installId;
        row.githubRepoId = repoId;
        try {
          stores.jobs().insert(row);
        } catch (RuntimeException e) {
          // A race with a manual create (same full_name) is the only realistic cause. Treat as a
          // benign skip — the manual row already covers this pipeline file.
          LOGGER.log(
              Level.INFO,
              "[titan-github] auto-enable insert skipped for {0}: {1}",
              new Object[] {fullName, e.getMessage()});
        }
      } else if (yamlDiffers(match.pipelineScript, yaml) || displayNameDiffers(match, shortName)) {
        stores.jobs().updateScriptAndConfig(match.id, yaml, configJson, shortName);
      }
    }
    // Prune jobs whose source file disappeared (or whose YAML now fails to parse). Hard delete —
    // cascades to builds via the existing FK chain.
    for (JobRow j : existing) {
      if (j.fullName != null && !keepFullNames.contains(j.fullName)) {
        stores.jobs().delete(j.id);
      }
    }
  }

  @NonNull
  private static String stripExtension(@NonNull String filename) {
    int slash = filename.lastIndexOf('/');
    String s = slash >= 0 ? filename.substring(slash + 1) : filename;
    if (s.endsWith(".yml")) return s.substring(0, s.length() - 4);
    if (s.endsWith(".yaml")) return s.substring(0, s.length() - 5);
    return s;
  }

  private static boolean yamlDiffers(String oldScript, String newScript) {
    String a = oldScript == null ? "" : oldScript;
    String b = newScript == null ? "" : newScript;
    return !a.equals(b);
  }

  private static boolean displayNameDiffers(@NonNull JobRow row, @NonNull String shortName) {
    return !shortName.equals(row.displayName);
  }

  /** Local minimal JSON-string escape — mirrors {@code JobsApi#jsonEscape} to avoid a dep here. */
  @NonNull
  private static String jsonEscape(@NonNull String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  @NonNull
  private static String readContent(@NonNull GHContent entry) throws IOException {
    // GHContent#getContent() returns the inline base64-decoded UTF-8 text. For files > 1MB
    // GitHub returns encoding="none" and kohsuke throws — that's a fail-loud case the YAML
    // scanner should not silently swallow (pipeline files at that size are wrong).
    //
    // Note: avoid GHContent#read() — it routes through download_url, which the directory-listing
    // response often leaves null, and the resulting fallback chain ends up at a 404. getContent()
    // uses the inline base64 chunk kohsuke already has from the listing.
    if (entry.getSize() == 0) {
      return "";
    }
    return entry.getContent();
  }

  /**
   * Parse one file's content and project its model to a JSON metadata blob. A parser exception
   * lands as a {@code parse_error} row, not a thrown exception.
   */
  @NonNull
  private DiscoveredFile parseOne(
      @NonNull String filename, @NonNull String contentSha, @NonNull String content) {
    try {
      PipelineModel model = TitanYamlParser.parseAndValidate(content);
      String metadataJson = MAPPER.writeValueAsString(projectMetadata(model));
      return new DiscoveredFile(filename, contentSha, metadataJson, null, content);
    } catch (RuntimeException | JsonProcessingException e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      LOGGER.log(
          Level.INFO,
          "[titan-github] pipeline file failed to parse: {0}: {1}",
          new Object[] {filename, msg});
      return new DiscoveredFile(filename, contentSha, null, msg, null);
    }
  }

  /**
   * Project a {@link PipelineModel} to the minimal JSON the UI needs — see #833. The PipelineModel
   * is intentionally NOT serialised directly: it carries internal engine state.
   */
  @NonNull
  static Map<String, Object> projectMetadata(@NonNull PipelineModel model) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("agent", model.getAgent());
    List<Map<String, Object>> stages = new ArrayList<>();
    for (StageModel stage : model.getStages()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("id", stage.getId());
      s.put("name", stage.getName());
      s.put("stepCount", stage.getSteps().size());
      stages.add(s);
    }
    out.put("stages", stages);
    List<Map<String, Object>> triggers = new ArrayList<>();
    for (TriggerModel t : model.getTriggers()) {
      Map<String, Object> tr = new LinkedHashMap<>();
      if (t.getCron() != null) {
        tr.put("type", "cron");
        tr.put("cron", t.getCron());
      } else if (t.getGithub() != null) {
        tr.put("type", "github");
        tr.put("events", t.getGithub().getEvents());
        tr.put("branches", t.getGithub().getBranches());
      }
      triggers.add(tr);
    }
    out.put("triggers", triggers);
    List<Map<String, Object>> params = new ArrayList<>();
    for (ParameterModel p : model.getParameters()) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("name", p.getName());
      pm.put("type", p.getType());
      pm.put("required", p.isRequired());
      // hasDefault drives webhook auto-dispatch skip (issue #919): a required-no-default
      // parameter cannot be satisfied by a webhook event (which supplies no params), so the
      // build must be skipped rather than enqueued doomed-to-fail.
      pm.put("hasDefault", p.getDefaultValue() != null);
      params.add(pm);
    }
    out.put("parameters", params);
    return out;
  }

  private static boolean isYamlFile(@NonNull String name) {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".yml") || lower.endsWith(".yaml");
  }

  /**
   * Mark the installation suspended on auth failure (401). The cached install token is also evicted
   * so the next sync attempt re-mints.
   */
  private void handleAuthFailure(long installId, @NonNull GithubApiException e) {
    if (e.status() == 401) {
      stores.githubInstallations().markSuspended(installId);
      appService.invalidateInstallationToken(installId);
      LOGGER.log(
          Level.WARNING,
          "[titan-github] install {0} suspended — GitHub returned 401 (token revoked or App "
              + "uninstalled)",
          installId);
    }
  }

  @NonNull
  private static GithubApiException httpFail(@NonNull String op, @NonNull HttpException e) {
    return new GithubApiException(
        op + " failed: HTTP " + e.getResponseCode() + ": " + e.getMessage(),
        e.getResponseCode(),
        e);
  }

  @NonNull
  private static GithubApiException ioFail(@NonNull String op, @NonNull IOException e) {
    return new GithubApiException(op + " I/O error: " + e.getMessage(), -1, e);
  }

  /**
   * In-memory staging record for one discovered file, before the delete-then-insert flush. {@code
   * rawYaml} carries the unmodified file bytes — we need it to populate {@code
   * titan.jobs.pipeline_script} when auto-creating job rows (design 66). It's {@code null} for
   * parse-error rows (we still persist the discovered row but never auto-enable a malformed YAML).
   */
  private record DiscoveredFile(
      @NonNull String filename,
      @NonNull String contentSha,
      String parsedMetadata,
      String parseError,
      String rawYaml) {}
}
