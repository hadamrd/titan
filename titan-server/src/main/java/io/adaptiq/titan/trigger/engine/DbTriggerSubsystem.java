package io.adaptiq.titan.trigger.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerCodec;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registers Titan with the trigger engine (design/50, Tier 3).
 *
 * <p>This is the <em>only</em> class the engine needs in order to schedule Titan builds: it
 * enumerates the persisted {@link Job}s as {@link TriggerOwner}s and supplies the {@link
 * DbTriggerStore}. The Quarkus CDI container discovers it via {@code @ApplicationScoped}; when
 * ReleaseFlow's CD engine adopts the module it registers its own {@code TriggerSubsystem} alongside
 * this one — no engine change (design/50 D2).
 *
 * <p>Triggers per owner are reconstructed from {@code titan.jobs.config_json} (key {@code
 * triggers}) using {@link TriggerCodec}.
 */
@ApplicationScoped
public class DbTriggerSubsystem implements TriggerSubsystem {

  private static final Logger LOGGER = Logger.getLogger(DbTriggerSubsystem.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TitanStores stores;
  private final JobService jobs;
  private final DbTriggerStore store;
  private final Map<String, TriggerCodec.TriggerReader> readers;

  DbTriggerSubsystem(TitanStores stores, JobService jobs) {
    this.stores = stores;
    this.jobs = jobs;
    this.store = new DbTriggerStore(stores);
    this.readers = TriggerCodec.readersFromDescriptors();
  }

  @Override
  @NonNull
  public Collection<TriggerOwner> owners() {
    List<TriggerOwner> out = new ArrayList<>();
    for (Job job : jobs.listAll()) {
      if (job.id() == 0) {
        continue;
      }
      List<Trigger> triggers = parseTriggers(job);
      out.add(new DbTriggerOwner(job, triggers));
    }
    return out;
  }

  @Override
  @NonNull
  public TriggerStore store() {
    return store;
  }

  /** Parse the {@code triggers} array out of {@code config_json}; empty list on any error. */
  @NonNull
  private List<Trigger> parseTriggers(@NonNull Job job) {
    String configJson = job.configJson();
    if (configJson == null || configJson.isBlank()) {
      return List.of();
    }
    try {
      JsonNode root = MAPPER.readTree(configJson);
      JsonNode triggers = root.path("triggers");
      return TriggerCodec.read(triggers, readers);
    } catch (IOException e) {
      LOGGER.log(
          Level.WARNING,
          "[trigger] could not parse config_json triggers for job " + job.fullName(),
          e);
      return List.of();
    }
  }
}
