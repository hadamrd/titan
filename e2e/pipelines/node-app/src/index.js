// Minimal real-shaped Node module — the build step copies this to dist/.
// Dependency-free so the fixture installs in milliseconds on the worker.

function greet(name) {
  if (typeof name !== 'string' || name.length === 0) {
    throw new TypeError('greet() requires a non-empty string');
  }
  return `hello, ${name}`;
}

module.exports = { greet };

if (require.main === module) {
  console.log(greet('titan'));
}
