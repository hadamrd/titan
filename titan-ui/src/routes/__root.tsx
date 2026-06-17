import { createRootRoute, Outlet, redirect, useRouterState } from '@tanstack/react-router'
import { Sidebar } from '@/components/Sidebar'
import { TopBar } from '@/components/TopBar'
import { CommandPalette } from '@/components/CommandPalette'
import { getAccessToken } from '@/auth/tokenStore'

const PUBLIC_PATHS = new Set<string>(['/login', '/login/callback'])

export const Route = createRootRoute({
  component: RootLayout,
  // Auth guard: every non-public path requires an access token. tokenStore is
  // populated by AuthProvider — beforeLoad runs outside the React tree.
  beforeLoad: ({ location }) => {
    if (PUBLIC_PATHS.has(location.pathname)) return
    if (!getAccessToken()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
})

function RootLayout() {
  const { pathname } = useRouterState({ select: (s) => s.location })
  const isPublic = PUBLIC_PATHS.has(pathname)

  // Public routes (login + callback) get a clean centred shell — no top
  // bar, no sidebar. Preserved from the previous root layout. Note: we
  // still use the v2 token palette via .app/tokens.css default body.
  if (isPublic) {
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '32px 24px',
          background: 'var(--bg)',
        }}
      >
        <main>
          <Outlet />
        </main>
      </div>
    )
  }

  return (
    <div className="app">
      <Sidebar />
      <div className="main-col">
        <TopBar />
        <main className="content-scroll">
          {/*
            Each route now owns its own <PageContainer> (UX chart H1) — the
            shared frame supplies max-width, centring, and px-6 py-6 padding
            per-page so a form can be narrow while a dense table goes wide.
            The legacy `.page` wrapper (fixed 1480px + padding) was removed so
            routes are not double-framed. `tab-pane` is kept purely for the
            route-transition fade animation.
          */}
          <div className="tab-pane">
            <Outlet />
          </div>
        </main>
      </div>
      <CommandPalette />
    </div>
  )
}
