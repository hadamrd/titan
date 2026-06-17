package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.flow.parser.LibraryFetcher;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The built-in {@code libraryCall} — fetch a shared library by immutable ref, then call one {@code
 * vars/<file>.groovy::<method>(Map)} as a single atomic DAG node (design/53).
 *
 * <p>Not invoked directly by users. The parser rewrites a {@code <alias>.<method>:} step into a
 * {@code libraryCall} step with arguments {@code {library, file, method, args}}, where {@code
 * <alias>} is declared in the pipeline-level {@code libraries:} object map.
 *
 * <p>Sibling to {@link ScriptStepHandler}. ScriptStepHandler runs a free-form Groovy {@code body}
 * with libraries pre-staged at synthesis time. {@code libraryCall} is the "call one method as an
 * atomic task" shape: fetched per-call by coordinate, one method invocation as the whole step,
 * atomic retry on failure. Both share {@link GroovyStepBinding} — the method uses bare {@code sh},
 * {@code echo}, {@code error}, {@code setOutput} exactly like a classic shared library.
 */
public final class LibraryStepHandler implements StepHandler {

  private final LibraryFetcher fetcher;

  public LibraryStepHandler(Path libraryCacheRoot) {
    this.fetcher = new LibraryFetcher(libraryCacheRoot);
  }

  @Override
  public String descriptorId() {
    return "libraryCall";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "libraryCall",
        "Library call",
        "Call one vars/<file>.groovy::<method>(Map) from a shared library, as an "
            + "atomic DAG node. Invoked through the parser's dotted-step dispatch — "
            + "see design/53.",
        List.of(
            ParamSpec.required("library", "string", "Library coordinate — <git-url>@<ref>."),
            ParamSpec.required("file", "string", "The vars/<file>.groovy basename."),
            ParamSpec.required(
                "method",
                "string",
                "The method name on the file (use 'call' for the conventional idiom)."),
            ParamSpec.optional(
                "libraryCredential",
                "string",
                "Name of a credential resolved on the worker via SecretProvider "
                    + "(design/40). Authenticates the git fetch for a private "
                    + "library coordinate."),
            ParamSpec.optional(
                "args", "map", "Arguments passed as a Map to the function's method(Map).")));
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    String coordinate = request.argString("library");
    if (coordinate == null || coordinate.isBlank()) {
      return StepResult.failed("libraryCall has no 'library' coordinate");
    }
    String file = request.argString("file");
    if (file == null || file.isBlank()) {
      return StepResult.failed("libraryCall has no 'file'");
    }
    String method = request.argString("method", "call");
    String credentialName = request.argString("libraryCredential");
    Map<String, Object> args = asMap(request.arguments().get("args"));

    Path checkout;
    try {
      checkout =
          (credentialName == null || credentialName.isBlank())
              ? fetcher.resolve(coordinate)
              : fetcher.resolve(coordinate, credentialName);
    } catch (RuntimeException e) {
      return StepResult.failed(
          "libraryCall could not fetch '" + coordinate + "': " + e.getMessage());
    }
    Path script = checkout.resolve("vars").resolve(file + ".groovy");
    if (!Files.isRegularFile(script)) {
      return StepResult.failed("library has no vars/" + file + ".groovy");
    }

    try (groovy.lang.GroovyClassLoader gcl =
        new groovy.lang.GroovyClassLoader(LibraryStepHandler.class.getClassLoader())) {
      groovy.lang.Binding binding = GroovyStepBinding.forStep(request);
      groovy.lang.Script instance =
          (groovy.lang.Script)
              gcl.parseClass(script.toFile()).getDeclaredConstructor().newInstance();
      instance.setBinding(binding);
      ((groovy.lang.GroovyObject) instance).invokeMethod(method, args);
      return StepResult.success();
    } catch (groovy.lang.MissingMethodException e) {
      return StepResult.failed("vars/" + file + ".groovy has no method '" + method + "'");
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      return StepResult.failed(
          "libraryCall " + file + "." + method + " failed: " + cause.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }
}
