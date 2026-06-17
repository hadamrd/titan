package io.adaptiq.titan.trigger.engine;

/**
 * A listener-driven source of {@link io.adaptiq.titan.trigger.TriggerEvent}s (design/51 D3).
 *
 * <p>Where {@code TriggerEngine}'s poll tick is the periodic ingress, a {@code TriggerSource} is
 * the <em>push</em> ingress for anything that is not an HTTP request: a Kafka consumer loop, a
 * Kubernetes watch, an AMQP/NATS subscription. An implementation:
 *
 * <ol>
 *   <li>is a Quarkus {@code @ApplicationScoped} CDI bean — discovered via {@code @Inject
 *       Instance<TriggerSource>};
 *   <li>stands up its listener in {@link #start()} (a blocking consumer thread is fine — the engine
 *       never blocks; the thread lives inside the source);
 *   <li>on each event, builds a {@code TriggerEvent} and calls {@code
 *       TriggerEngine.deliver(event)};
 *   <li>releases everything in {@link #stop()}.
 * </ol>
 *
 * <p>Lifecycle is managed by {@link TriggerSourceBootstrap}: {@link #start()} once on Quarkus
 * startup, {@link #stop()} on shutdown. HTTP webhooks are <em>not</em> {@code TriggerSource}s —
 * Quarkus already routes requests, so a webhook is a request-driven REST endpoint that needs no
 * lifecycle (design/51 D4).
 *
 * <p><strong>HA contract (design/52 D6).</strong> In a multi-controller Titan deployment every
 * controller starts every {@code TriggerSource}. The engine cannot de-duplicate an event it
 * receives once per controller — so a source <em>must</em> de-duplicate delivery at its broker.
 */
public interface TriggerSource {

  /** Begin listening for events. */
  void start();

  /** Stop listening and release all resources. */
  void stop();
}
