package io.adaptiq.titan.worker.step;

/**
 * A sink for a step's published outputs — the {@code setOutput(key, value)} contract (Chunk 32A,
 * design/29 §6 / design/32 §3.1).
 *
 * <p>Outputs are written <em>during</em> execution, not only on return, so a step that fails
 * part-way still publishes what it had produced (design/32 §4). The worker folds the collected
 * outputs into the completed node's {@code result_json}, which the orchestrator's {@code outputsOf}
 * resolves for downstream {@code ${{ … }}} references (Chunk 6E).
 *
 * <p>Values are small JSON scalars — ids, versions, flags. Large data goes to artifact storage;
 * only its handle travels here (design/29 §6).
 */
public interface OutputSink {

  /**
   * Publish a step output.
   *
   * @param key the output name
   * @param value a JSON scalar (String / Number / Boolean); a {@code CharSequence} is flattened to
   *     a {@code String} so the {@code result_json} round-trips cleanly
   */
  void put(String key, Object value);
}
