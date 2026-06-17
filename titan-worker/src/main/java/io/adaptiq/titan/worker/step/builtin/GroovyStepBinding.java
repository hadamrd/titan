package io.adaptiq.titan.worker.step.builtin;

import groovy.lang.Binding;
import groovy.lang.Closure;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.StepRequest;
import java.util.List;

/**
 * The shared Groovy {@link Binding} used by both {@code script} and {@code libraryStep} — the four
 * builtins ({@code sh}, {@code echo}, {@code error}, {@code setOutput}), resolving against the
 * request's collaborators.
 *
 * <p>Lives separately so two handlers don't duplicate the binding plumbing. The closure shapes are
 * {@code sh "..."}, {@code echo "..."}, with no {@code titan.} prefix.
 */
final class GroovyStepBinding {

  private GroovyStepBinding() {}

  static Binding forStep(StepRequest request) {
    Binding binding = new Binding();
    LogSink log = request.log();

    binding.setVariable(
        "echo",
        new Closure<Object>(GroovyStepBinding.class) {
          public Object doCall(Object message) {
            log.line("stdout", String.valueOf(message));
            return null;
          }
        });

    binding.setVariable(
        "error",
        new Closure<Object>(GroovyStepBinding.class) {
          public Object doCall(Object message) {
            throw new RuntimeException(String.valueOf(message));
          }
        });

    binding.setVariable(
        "setOutput",
        new Closure<Object>(GroovyStepBinding.class) {
          public Object doCall(Object key, Object value) {
            request.outputs().put(String.valueOf(key), value);
            return null;
          }
        });

    binding.setVariable(
        "sh",
        new Closure<Object>(GroovyStepBinding.class) {
          public Object doCall(Object scriptText) {
            try {
              int exit =
                  request
                      .executor()
                      .run(
                          List.of("sh", "-c", String.valueOf(scriptText)),
                          request.workDir(),
                          request.env(),
                          log);
              if (exit != 0) {
                throw new RuntimeException("sh exited with code " + exit);
              }
            } catch (RuntimeException re) {
              throw re;
            } catch (Exception e) {
              throw new RuntimeException("sh execution error: " + e.getMessage(), e);
            }
            return null;
          }
        });

    return binding;
  }
}
