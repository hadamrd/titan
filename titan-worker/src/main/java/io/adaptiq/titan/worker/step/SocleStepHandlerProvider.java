package io.adaptiq.titan.worker.step;

import io.adaptiq.titan.worker.step.builtin.ArchiveArtifactsStepHandler;
import io.adaptiq.titan.worker.step.builtin.DeleteDirStepHandler;
import io.adaptiq.titan.worker.step.builtin.EchoStepHandler;
import io.adaptiq.titan.worker.step.builtin.ErrorStepHandler;
import io.adaptiq.titan.worker.step.builtin.FileExistsStepHandler;
import io.adaptiq.titan.worker.step.builtin.GitStepHandler;
import io.adaptiq.titan.worker.step.builtin.GitTagStepHandler;
import io.adaptiq.titan.worker.step.builtin.HttpRequestStepHandler;
import io.adaptiq.titan.worker.step.builtin.JUnitStepHandler;
import io.adaptiq.titan.worker.step.builtin.K8sApplyStepHandler;
import io.adaptiq.titan.worker.step.builtin.LibraryStepHandler;
import io.adaptiq.titan.worker.step.builtin.ReadFileStepHandler;
import io.adaptiq.titan.worker.step.builtin.ScriptStepHandler;
import io.adaptiq.titan.worker.step.builtin.SetOutputStepHandler;
import io.adaptiq.titan.worker.step.builtin.ShellStepHandler;
import io.adaptiq.titan.worker.step.builtin.WriteFileStepHandler;
import java.util.List;

/**
 * The {@link StepHandlerProvider} for Titan's socle — the universal core steps (design/42 §2,
 * §4.2).
 *
 * <p>The socle dogfoods the SPI: these built-in handlers are discovered through the exact same
 * {@code ServiceLoader} path as any third-party Tier-2 jar — there is no privileged built-in code
 * path. This provider is registered via the {@code META-INF/services} resource in this module.
 *
 * <p>{@code ScriptStepHandler} needs the shared-libraries root, so it is built here from {@link
 * StepHandlerContext#librariesRoot()} rather than at a hardcoded call site.
 */
public final class SocleStepHandlerProvider implements StepHandlerProvider {

  @Override
  public List<StepHandler> handlers(StepHandlerContext context) {
    return List.of(
        new ShellStepHandler(),
        // script — runs a Groovy body; binds staged vars/<name>.groovy from libraryCacheRoot
        // so the body can call shared-library functions (issue #681).
        new ScriptStepHandler(context.libraryCacheRoot()),
        // libraryCall — invoked by the parser's <alias>.<method> dispatch (design/53).
        new LibraryStepHandler(context.libraryCacheRoot()),
        new WriteFileStepHandler(),
        new ReadFileStepHandler(),
        new FileExistsStepHandler(),
        new DeleteDirStepHandler(),
        // archiveArtifacts — persists build outputs through the ArtifactSink (design/41).
        new ArchiveArtifactsStepHandler(),
        // junit — parses JUnit/surefire XML reports; the #1 test-reporting migration step.
        new JUnitStepHandler(),
        // httpRequest — one HTTP(S) call: webhooks, notifications, API gates (design/50).
        new HttpRequestStepHandler(),
        // k8sApply — applies a Kubernetes manifest via `kubectl apply -f` (design/62, #242).
        new K8sApplyStepHandler(),
        // setOutput — publishes named key/value state into the pipeline.
        new SetOutputStepHandler(),
        // error / echo — small infrastructure-free flow primitives. (sleep is now a
        // durable, controller-native step — handled by the orchestrator, not a worker.)
        new ErrorStepHandler(),
        new EchoStepHandler(),
        // git is the #1 migration-frequency step after sh/script (design/32 §12 D5);
        // checkout is the generic SCM alias delegating to the same implementation.
        new GitStepHandler(GitStepHandler.GIT),
        new GitStepHandler(GitStepHandler.CHECKOUT),
        // gitTag — annotates + pushes a tag; release pipelines' first-class tagging (#758).
        new GitTagStepHandler());
  }

  @Override
  public String describe() {
    return "Titan socle steps";
  }
}
