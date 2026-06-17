// initBindings — sample shared-library method (design/29 §10 fixture).
//
// An ordinary shared-library vars/ function: a Titan `script` step's Groovy body
// calls it directly. The shared library is loaded into the agent's
// Groovy runtime (design/26 Tier C); the controller never runs this code.

def call(Map args = [:]) {
    def env = args.env ?: 'dev'
    echo "[moab-shared] initBindings env=${env}"
    return [
        component: 'moab-core',
        env      : env,
        cacheKey : "moab-${env}",
    ]
}
