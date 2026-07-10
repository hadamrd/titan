// vitest suite for fixture #1130. EXACTLY ONE failing test, on purpose.
//
// The failing case is the SRE's day-zero failure-triage trigger: the e2e spec
// `golden-path-failure-triage.spec.ts` asserts:
//   * build transitions to FAILURE within 30s of trigger
//   * the failing-step log contains a line matching /FAIL /
//   * after the spec patches `expect(1 + 1).toBe(3)` -> `.toBe(2)`, the next
//     build is SUCCESS. The patch is applied to the BUILD's workspace copy
//     via the pipeline's E2E_PATCH_POINT anchor (#161) — THIS file on disk is
//     never edited at run time. A host-side edit here raced concurrent specs'
//     tar copies and produced a phantom SUCCESS on a should-fail build.
//
// Do NOT add extra failing tests here — the spec asserts "exactly one failing
// test". Add new passing cases freely. The sed in the triage spec's leg 4 and
// the unit-test stage's guards match this file's exact text — keep the
// intentional assertion literally `expect(sum(1, 1)).toBe(3);`.

import { describe, it, expect } from 'vitest';
import { sum } from './sum.js';

describe('sum', () => {
  it('adds two positive integers', () => {
    expect(sum(1, 2)).toBe(3);
  });

  it('adds with zero', () => {
    expect(sum(0, 5)).toBe(5);
  });

  // The intentional failure. Vitest reports this as "FAIL src/sum.test.js >
  // sum > intentional regression …" — the e2e spec greps the worker log for
  // the literal "FAIL " token to assert the log viewer scrolled to the
  // right line.
  it('intentional regression — expect(1 + 1).toBe(3)', () => {
    expect(sum(1, 1)).toBe(3);
  });
});
