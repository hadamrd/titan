// buildX — sample shared-library method (design/29 §10 migration fixture).
//
// The team's existing build helper. Reused verbatim from a Titan `script`
// step — the migration changes the orchestration (a DAG), not the step code.

def call(Map args) {
    def component = args.component ?: error('buildX: component is required')
    def cache     = args.cache ?: false
    echo "[moab-shared] buildX component=${component} cache=${cache}"
    sh "make build COMPONENT=${component}"
    return [component: component, built: true]
}
