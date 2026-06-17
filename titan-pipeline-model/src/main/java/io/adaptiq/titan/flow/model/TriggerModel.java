package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A build trigger declared in a {@code titan-pipeline.yml} {@code triggers:} block (design/50 D7).
 *
 * <p>This is the parsed-PDL representation of a trigger; it is deliberately decoupled from the
 * scheduler module's {@code Trigger} hierarchy. {@code TitanJob} projects a parsed {@code
 * TriggerModel} list onto its effective trigger set at config-save time.
 *
 * <p>Discriminated union (issue #397): exactly one of {@link #getCron()} or {@link #getGithub()} is
 * non-null. The parser enforces this; consumers branch on the type discriminator, never sniff field
 * shape.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class TriggerModel {

  /**
   * The cron expression — standard 5-field syntax with 'H' hashing (design/50 D3). Mutually
   * exclusive with {@link #github}.
   */
  @Nullable private String cron;

  /** The GitHub-webhook trigger config (issue #397). Mutually exclusive with {@link #cron}. */
  @Nullable private GithubTriggerModel github;

  /** The GitLab-webhook trigger config (issue #1078). Mutually exclusive with the others. */
  @Nullable private GitlabTriggerModel gitlab;

  /** The Bitbucket-webhook trigger config (issue #1079). Mutually exclusive with the others. */
  @Nullable private BitbucketTriggerModel bitbucket;

  /** Default constructor for Jackson deserialization. */
  public TriggerModel() {}

  /** Convenience constructor for a cron trigger. */
  public TriggerModel(@NonNull String cron) {
    this.cron = cron;
  }

  /** Convenience constructor for a GitHub webhook trigger. */
  public TriggerModel(@NonNull GithubTriggerModel github) {
    this.github = github;
  }

  /** Convenience constructor for a GitLab webhook trigger. */
  public TriggerModel(@NonNull GitlabTriggerModel gitlab) {
    this.gitlab = gitlab;
  }

  /** Convenience constructor for a Bitbucket webhook trigger. */
  public TriggerModel(@NonNull BitbucketTriggerModel bitbucket) {
    this.bitbucket = bitbucket;
  }

  @Nullable
  public String getCron() {
    return cron;
  }

  public void setCron(@Nullable String cron) {
    this.cron = cron;
  }

  @Nullable
  public GithubTriggerModel getGithub() {
    return github;
  }

  public void setGithub(@Nullable GithubTriggerModel github) {
    this.github = github;
  }

  @Nullable
  public GitlabTriggerModel getGitlab() {
    return gitlab;
  }

  public void setGitlab(@Nullable GitlabTriggerModel gitlab) {
    this.gitlab = gitlab;
  }

  @Nullable
  public BitbucketTriggerModel getBitbucket() {
    return bitbucket;
  }

  public void setBitbucket(@Nullable BitbucketTriggerModel bitbucket) {
    this.bitbucket = bitbucket;
  }

  /**
   * The parsed PDL shape of a {@code github:} trigger entry (issue #397).
   *
   * <pre>
   * triggers:
   *   - github:
   *       branches: [trunk, 'feat/**']
   *       events: [push, pull_request]
   *       credentialsId: github-webhook-secret
   * </pre>
   *
   * <p>The HMAC secret is NEVER stored in plaintext on the model — only its {@code credentialsId}.
   * The server resolves the actual secret via {@code CredentialsService} at receive time.
   */
  public static final class GithubTriggerModel {

    private List<String> branches = new ArrayList<>();
    private List<String> events = new ArrayList<>();
    @Nullable private String credentialsId;

    /** Default constructor for Jackson deserialization. */
    public GithubTriggerModel() {}

    @NonNull
    public List<String> getBranches() {
      return branches;
    }

    public void setBranches(@Nullable List<String> branches) {
      this.branches = branches == null ? new ArrayList<>() : new ArrayList<>(branches);
    }

    @NonNull
    public List<String> getEvents() {
      return events;
    }

    public void setEvents(@Nullable List<String> events) {
      this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }

    @Nullable
    public String getCredentialsId() {
      return credentialsId;
    }

    public void setCredentialsId(@Nullable String credentialsId) {
      this.credentialsId = credentialsId;
    }
  }

  /**
   * The parsed PDL shape of a {@code gitlab:} trigger entry (issue #1078).
   *
   * <pre>
   * triggers:
   *   - gitlab:
   *       branches: [trunk, 'feat/**']
   *       events: [push, merge_request, tag_push]
   *       credentialsId: gitlab-webhook-token
   * </pre>
   *
   * <p>The shared-secret token is NEVER stored in plaintext on the model — only its {@code
   * credentialsId}. The server resolves the actual token via {@code CredentialsService} at receive
   * time and constant-time-compares it against {@code X-Gitlab-Token}.
   */
  public static final class GitlabTriggerModel {

    private List<String> branches = new ArrayList<>();
    private List<String> events = new ArrayList<>();
    @Nullable private String credentialsId;

    /** Default constructor for Jackson deserialization. */
    public GitlabTriggerModel() {}

    @NonNull
    public List<String> getBranches() {
      return branches;
    }

    public void setBranches(@Nullable List<String> branches) {
      this.branches = branches == null ? new ArrayList<>() : new ArrayList<>(branches);
    }

    @NonNull
    public List<String> getEvents() {
      return events;
    }

    public void setEvents(@Nullable List<String> events) {
      this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }

    @Nullable
    public String getCredentialsId() {
      return credentialsId;
    }

    public void setCredentialsId(@Nullable String credentialsId) {
      this.credentialsId = credentialsId;
    }
  }

  /**
   * The parsed PDL shape of a {@code bitbucket:} trigger entry (issue #1079).
   *
   * <pre>
   * triggers:
   *   - bitbucket:
   *       branches: [trunk, 'feat/**']
   *       events: [push, pull_request]
   *       credentialsId: bitbucket-webhook-secret
   * </pre>
   *
   * <p>Bitbucket signs the delivery body (HMAC-SHA256 in {@code X-Hub-Signature}) rather than
   * sending a shared-secret token, so the HMAC secret is NEVER stored in plaintext on the model —
   * only its {@code credentialsId}. The server resolves the actual secret via {@code
   * CredentialsService} at receive time and recomputes the signature over the raw body.
   */
  public static final class BitbucketTriggerModel {

    private List<String> branches = new ArrayList<>();
    private List<String> events = new ArrayList<>();
    @Nullable private String credentialsId;

    /** Default constructor for Jackson deserialization. */
    public BitbucketTriggerModel() {}

    @NonNull
    public List<String> getBranches() {
      return branches;
    }

    public void setBranches(@Nullable List<String> branches) {
      this.branches = branches == null ? new ArrayList<>() : new ArrayList<>(branches);
    }

    @NonNull
    public List<String> getEvents() {
      return events;
    }

    public void setEvents(@Nullable List<String> events) {
      this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }

    @Nullable
    public String getCredentialsId() {
      return credentialsId;
    }

    public void setCredentialsId(@Nullable String credentialsId) {
      this.credentialsId = credentialsId;
    }
  }
}
