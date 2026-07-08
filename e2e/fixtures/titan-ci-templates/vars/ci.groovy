// titan-ci-templates — a reusable Titan shared-CI library fragment.
//
// Consumed via a pipeline-level `libraries:` block (design/53):
//
//   libraries:
//     ci: "https://github.com/hadamrd/titan-ci-templates.git@v1"
//   stages:
//     - stage: Build
//       steps:
//         - ci.buildAndTest: { profile: fast }   # -> vars/ci.groovy::buildAndTest(Map)
//
// The `<alias>.<method>:` step loads vars/<alias>.groovy and calls <method>(Map) on the
// worker (LibraryStepHandler), using bare `sh`/`echo` exactly like a Jenkins shared library.

// A parametrised build+test fragment. The marker below lives ONLY in this library repo — a
// consumer pipeline never inlines it, so observing it in a build proves the library was
// actually fetched + executed (issue #1228 acceptance).
def buildAndTest(Map args = [:]) {
  def profile = args.profile ?: 'default'
  echo "titan-ci-templates: buildAndTest profile=${profile}"
  // The unique, library-only marker. Written to the workspace so the consumer pipeline can
  // archive it and an e2e can assert its bytes round-trip.
  sh "echo TITAN_LIB_MARKER_buildAndTest_v1 profile=${profile} > lib-out.txt"
}
