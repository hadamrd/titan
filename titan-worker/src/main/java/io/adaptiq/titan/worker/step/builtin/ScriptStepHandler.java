package io.adaptiq.titan.worker.step.builtin;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject;
import groovy.lang.Script;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The built-in {@code script} step — runs an inline Groovy body on the agent at step execution
 * time. Bindings come from {@link GroovyStepBinding}: {@code sh}, {@code echo}, {@code error},
 * {@code setOutput}.
 *
 * <p><strong>Shared-library {@code vars/} binding.</strong> A {@code script} step payload may carry
 * a {@code libraries: [<name>, ...]} list. For each named library, the handler loads every {@code
 * vars/<name>.groovy} under {@code <libraryCacheRoot>/<libName>/vars/} and binds it into the body's
 * {@link Binding} as a callable {@link Closure} — so {@code initBindings(env: 'ci')} resolves to
 * the {@code call(Map)} method of {@code vars/initBindings.groovy}, exactly as a classic
 * shared-library {@code vars/} function does. (Issue #681 fix — the body's MetaClass had no path to
 * the loaded {@code vars/} files; the fix is to inject them as Binding variables so Groovy's normal
 * binding lookup resolves them at the body site.) The {@code libraryCall} step (atomic single-call
 * shape, design/53) still exists for fetched libraries; this is the staged-libraries path used by a
 * free-form {@code script} body.
 *
 * <p><strong>Classloader isolation (design/26 Tier C).</strong> The {@link GroovyClassLoader}'s
 * parent is the worker's own loader, which carries no CI-framework classes — a body that tries to
 * import unavailable framework packages fails to compile cleanly.
 */
public final class ScriptStepHandler implements StepHandler {

  private final Path libraryCacheRoot;

  /**
   * Used by tests and any call site that does not stage shared libraries. A {@code script} body
   * that references a {@code vars/} method will then fail with the usual {@code
   * MissingMethodException}, since the library directory is empty.
   */
  public ScriptStepHandler() {
    this(null);
  }

  public ScriptStepHandler(Path libraryCacheRoot) {
    this.libraryCacheRoot = libraryCacheRoot;
  }

  @Override
  public String descriptorId() {
    return "script";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "script",
        "Groovy script",
        "Runs an inline Groovy body on the agent. The body may call sh / echo / error / "
            + "setOutput, plus any vars/<name>.groovy method from the staged libraries.",
        List.of(
            ParamSpec.required("runtime", "string", "The body's runtime — 'groovy'."),
            ParamSpec.required("body", "string", "The Groovy source to run."),
            ParamSpec.optional(
                "libraries",
                "list",
                "Names of staged shared libraries to expose: each vars/<name>.groovy "
                    + "becomes callable as <name>(args) from the body.")));
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    String body = request.argString("body");
    if (body == null || body.isBlank()) {
      return StepResult.failed("script step has no 'body'");
    }
    Binding binding = GroovyStepBinding.forStep(request);
    List<String> libraries = request.argStringList("libraries");

    try (GroovyClassLoader gcl = new GroovyClassLoader(ScriptStepHandler.class.getClassLoader())) {
      // Load and bind each declared library's vars/*.groovy files. A vars/<name>.groovy file
      // is bound under the variable <name> as a Closure that invokes the script's call(...) —
      // so the body can write `initBindings(env: 'ci')` exactly as in a classic
      // shared-library body. The loaded scripts share the body's binding so they see echo/sh.
      bindStagedLibraries(libraries, gcl, binding, request.log());

      request.log().system("running groovy script step");
      Script script =
          (Script)
              gcl.parseClass(body, "titanScriptBody.groovy").getDeclaredConstructor().newInstance();
      script.setBinding(binding);
      script.run();
      return StepResult.success();
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      return StepResult.failed("groovy script step failed: " + cause.getMessage());
    }
  }

  /**
   * Stage the declared shared libraries into the binding. A library {@code <libName>} resolves to
   * {@code <libraryCacheRoot>/<libName>/vars/}; each {@code <name>.groovy} in that directory is
   * compiled, instantiated against the same binding, and bound under {@code <name>} as a {@link
   * Closure} dispatching to the script's {@code call(...)} method. Same-named bindings are not
   * overridden: an earlier library wins, which is the conventional precedence.
   *
   * <p>A missing library directory is reported and skipped — the body will fail later with a
   * regular {@code MissingMethodException}, which is the correct, loud failure mode. A library file
   * that fails to compile is reported and skipped for the same reason.
   */
  private void bindStagedLibraries(
      List<String> libraries, GroovyClassLoader gcl, Binding binding, LogSink log)
      throws IOException {
    if (libraries.isEmpty()) {
      return;
    }
    if (libraryCacheRoot == null) {
      log.system("script step ignores 'libraries:' — no library cache root is configured");
      return;
    }
    for (String libName : libraries) {
      if (libName == null || libName.isBlank()) {
        continue;
      }
      Path varsDir = libraryCacheRoot.resolve(libName).resolve("vars");
      if (!Files.isDirectory(varsDir)) {
        log.system(
            "library '" + libName + "' has no vars/ directory at " + varsDir + " — skipping");
        continue;
      }
      List<Path> varsFiles = new ArrayList<>();
      try (var stream = Files.list(varsDir)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".groovy"))
            .sorted()
            .forEach(varsFiles::add);
      }
      for (Path file : varsFiles) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".groovy".length());
        if (binding.hasVariable(name)) {
          // First library wins (conventional precedence); do not silently shadow the four builtins.
          continue;
        }
        Script varsScript;
        try {
          varsScript =
              (Script) gcl.parseClass(file.toFile()).getDeclaredConstructor().newInstance();
        } catch (Exception e) {
          log.system(
              "library '"
                  + libName
                  + "' vars/"
                  + fileName
                  + " failed to load: "
                  + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
          continue;
        }
        // Share the body's binding so the vars/ script sees echo/sh/error/setOutput.
        varsScript.setBinding(binding);
        binding.setVariable(name, new VarsCallClosure(varsScript));
      }
      log.system("loaded shared library '" + libName + "'");
    }
  }

  /**
   * A {@link Closure} that invokes the wrapped vars/ script's {@code call(...)} method. Matches the
   * shared-library convention: {@code foo(args)} from a body resolves to {@code
   * vars/foo.groovy::call(args)}. Variadic on purpose so {@code foo()}, {@code foo(map)}, {@code
   * foo(a, b)} all reach the right Groovy overload via the script's MetaClass.
   */
  private static final class VarsCallClosure extends Closure<Object> {
    private static final long serialVersionUID = 1L;
    private final transient Script script;

    VarsCallClosure(Script script) {
      super(ScriptStepHandler.class);
      this.script = script;
    }

    @SuppressWarnings("unused") // invoked reflectively by Groovy
    public Object doCall(Object... args) {
      Object[] effective = args == null ? new Object[0] : args;
      return ((GroovyObject) script).invokeMethod("call", effective);
    }
  }
}
