#!/usr/bin/env bash
# Self-contained tests for quality-metrics.sh.
# Run: bash dev/git-hooks/test-quality-metrics.sh
#
# Adversarial coverage targets:
#  - cyclomatic complexity counts branches, not bytes
#  - collaborator count picks up `private final` + ignores primitives
#  - test:source ratio honours parallel test file under src/test/
#  - check mode is non-zero only under --strict
#  - empty / minimal file does not crash (defensive null/edge)
#  - method-boundary detection survives generics with "?>" in the signature

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/quality-metrics.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok: $1"; }
fail() { FAIL=$((FAIL+1)); echo "  FAIL: $1" >&2; }

mkjava() {
  local rel="$1"; shift
  local path="$TMP/$rel"
  mkdir -p "$(dirname "$path")"
  printf '%s\n' "$@" >"$path"
  echo "$path"
}

# ----- 1. cyclomatic complexity counts each branch keyword -----
f1=$(mkjava "src/main/java/Foo.java" \
  "package p;" \
  "public class Foo {" \
  "  public int run(int x) {" \
  "    if (x > 0) {" \
  "      for (int i = 0; i < x; i++) { if (i % 2 == 0 && i > 1) x++; }" \
  "    } else if (x < 0) {" \
  "      while (x < 0) { x++; }" \
  "    }" \
  "    return x;" \
  "  }" \
  "}")
out=$(bash "$SCRIPT" check "$f1" 2>&1 || true)
# Expected CC ~ 7 (under 10). Synthetic fixtures have no test file, so we
# accept the test-ratio warning and only assert no CC warning here.
if echo "$out" | grep -q "cyclomatic complexity"; then
  fail "low-CC method should not warn on CC — got: $out"
else
  ok "low-CC method does not warn on CC"
fi

# Build a method with 15+ branches → must warn.
big_method=$(mkjava "src/main/java/Big.java" \
  "package p;" \
  "public class Big {" \
  "  public int run(int x) {" \
  "    if(x>0){if(x>1){if(x>2){if(x>3){if(x>4){if(x>5){if(x>6){if(x>7){if(x>8){if(x>9){if(x>10){if(x>11){if(x>12){if(x>13){if(x>14){x++;}}}}}}}}}}}}}}}" \
  "    return x;" \
  "  }" \
  "}")
out=$(bash "$SCRIPT" check "$big_method" 2>&1 || true)
echo "$out" | grep -q "cyclomatic complexity" && ok "high-CC method warns" || fail "high-CC method should warn — got: $out"

# ----- 2. collaborator count -----
multi_collab=$(mkjava "src/main/java/Multi.java" \
  "package p;" \
  "public class Multi {" \
  "  private final FooDao a;" \
  "  private final BarDao b;" \
  "  private final BazDao c;" \
  "  private final QuxDao d;" \
  "  private final XyzDao e;" \
  "  private final OneDao f;" \
  "  private final TwoDao g;" \
  "  private final ThreeDao h;" \
  "  private final int notACollaborator = 5;" \
  "  public Multi(FooDao a,BarDao b,BazDao c,QuxDao d,XyzDao e,OneDao f,TwoDao g,ThreeDao h){this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;this.f=f;this.g=g;this.h=h;}" \
  "  public void run(){ a.q(); }" \
  "}")
out=$(bash "$SCRIPT" check "$multi_collab" 2>&1 || true)
echo "$out" | grep -q "collaborators injected" && ok "too-many collaborators warns" || fail "too-many collaborators should warn — got: $out"

# Primitive 'private final int' must NOT count as collaborator (independent oracle).
just_prims=$(mkjava "src/main/java/Prims.java" \
  "package p;" \
  "public class Prims {" \
  "  private final int a = 1;" \
  "  private final long b = 2;" \
  "  private final boolean c = true;" \
  "  public void run(){}" \
  "}")
out=$(bash "$SCRIPT" check "$just_prims" 2>&1 || true)
echo "$out" | grep -q "collaborators injected" && fail "primitive fields should not count as collaborators — got: $out" || ok "primitive fields are not collaborators"

# ----- 3. test:source LOC ratio — warns under threshold -----
no_test=$(mkjava "src/main/java/NoTest.java" \
  "package p; public class NoTest { void a(){} void b(){} void c(){} void d(){} void e(){} }")
out=$(bash "$SCRIPT" check "$no_test" 2>&1 || true)
echo "$out" | grep -q "test:source LOC ratio" && ok "missing test file warns" || fail "missing test file should warn — got: $out"

# Parallel test file pushes ratio above threshold.
src=$(mkjava "src/main/java/WithTest.java" "package p; public class WithTest { void run(){} }")
test=$(mkjava "src/test/java/WithTestTest.java" \
  "package p;" \
  "import org.junit.jupiter.api.Test;" \
  "public class WithTestTest {" \
  "  @Test void happy(){}" \
  "  @Test void sad(){}" \
  "  @Test void edge(){}" \
  "}")
# Run check from the tmpdir so relative paths line up.
out=$( cd "$TMP" && bash "$SCRIPT" check "src/main/java/WithTest.java" 2>&1 || true )
echo "$out" | grep -q "test:source LOC ratio" && fail "covered file should NOT warn — got: $out" || ok "covered file passes ratio check"

# ----- 4. check mode exit code -----
( cd "$TMP" && bash "$SCRIPT" check "src/main/java/Big.java" >/dev/null 2>&1 ) && ok "check exit=0 without --strict (informational)" || fail "check default exit should be 0"
( cd "$TMP" && bash "$SCRIPT" check --strict "src/main/java/Big.java" >/dev/null 2>&1 ) && fail "--strict should fail on warned file" || ok "--strict exit=1 on warned file"

# ----- 5. empty + minimal file does not crash -----
empty=$(mkjava "src/main/java/Empty.java" "")
bash "$SCRIPT" check "$empty" >/dev/null 2>&1 && ok "empty file does not crash" || fail "empty file crashed"

# ----- 6. generics in method signature -----
generics=$(mkjava "src/main/java/Gen.java" \
  "package p;" \
  "import java.util.Map;" \
  "public class Gen {" \
  "  public <T> Map<String, ? extends T> resolve(Map<String, T> in) {" \
  "    if (in == null) return null;" \
  "    return java.util.Collections.unmodifiableMap(in);" \
  "  }" \
  "}")
bash "$SCRIPT" check "$generics" >/dev/null 2>&1 && ok "generics in signature do not crash detector" || fail "generics signature crashed"

echo
echo "Quality-metrics tests: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
