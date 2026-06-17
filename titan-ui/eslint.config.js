/**
 * Titan UI lint rules.
 *
 * Focus: forbid native browser dialogs (window.confirm / alert / prompt and
 * bare confirm / alert / prompt calls). These render unstyled, unbranded
 * popups outside the React tree — they ignored the design system, missed
 * the dark theme, and (PR #1037) shipped to prod in profile.tsx. Use the
 * themed `ConfirmDialog` component instead (see
 * src/components/ui/ConfirmDialog.tsx).
 *
 * The config is deliberately minimal — no react/typescript-eslint plugin
 * rules, no type-aware linting. `pnpm type-check` and `pnpm test` already
 * cover correctness; this file exists to enforce the dialog ban and any
 * future cheap, codebase-wide guardrails.
 */
import tsParser from '@typescript-eslint/parser'

const DIALOG_MESSAGE =
  'Native browser dialogs are forbidden. Use the themed `ConfirmDialog` ' +
  'component from `@/components/ui/ConfirmDialog` (state-prop driven, ' +
  'matches the design system, theme-aware).'

export default [
  {
    ignores: [
      'dist/**',
      'build/**',
      'node_modules/**',
      'src/routeTree.gen.ts',
      'coverage/**',
    ],
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsParser,
      ecmaVersion: 2022,
      sourceType: 'module',
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
      globals: {
        window: 'readonly',
        document: 'readonly',
      },
    },
    linterOptions: {
      // Ignore inline `// eslint-disable-*` directives entirely. Two reasons:
      //   1. The codebase has historical disable comments referencing rules
      //      (react-hooks/*, @typescript-eslint/*) that this minimal config
      //      does not load, which would otherwise error with "Definition for
      //      rule 'X' was not found".
      //   2. We don't want anyone disabling the no-native-dialog rule inline —
      //      the whole point of the rule is that there is no escape hatch.
      noInlineConfig: true,
      reportUnusedDisableDirectives: 'off',
    },
    rules: {
      // Bans bare identifiers — `confirm(...)`, `alert(...)`, `prompt(...)`
      // resolve to the window globals via the global scope.
      'no-restricted-globals': [
        'error',
        { name: 'confirm', message: DIALOG_MESSAGE },
        { name: 'alert', message: DIALOG_MESSAGE },
        { name: 'prompt', message: DIALOG_MESSAGE },
      ],
      // Bans the explicit `window.confirm(...)` / `window.alert(...)` /
      // `window.prompt(...)` member-expression form, which `no-restricted-globals`
      // does NOT cover.
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "CallExpression[callee.type='MemberExpression'][callee.object.name='window'][callee.property.name='confirm']",
          message: DIALOG_MESSAGE,
        },
        {
          selector:
            "CallExpression[callee.type='MemberExpression'][callee.object.name='window'][callee.property.name='alert']",
          message: DIALOG_MESSAGE,
        },
        {
          selector:
            "CallExpression[callee.type='MemberExpression'][callee.object.name='window'][callee.property.name='prompt']",
          message: DIALOG_MESSAGE,
        },
      ],
    },
  },
]
