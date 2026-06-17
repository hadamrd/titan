import { createFileRoute, Outlet, useLocation, useNavigate } from '@tanstack/react-router'
import { useEffect } from 'react'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/Card'
import { TitanGlyph } from '@/components/TitanGlyph'
import { useAuth } from '@/auth/AuthProvider'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/login')({
  component: LoginPage,
})

function LoginPage() {
  const { signinRedirect, isLoading, isAuthenticated } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  useDocumentTitle('Sign in')

  // Already signed in (session restored from sessionStorage, or just came
  // back from the callback) — bounce to the redirect target / Overview.
  // Hook runs unconditionally; the early-return below is OK because it
  // happens after every hook is invoked.
  const isCallbackPath = location.pathname !== '/login'
  useEffect(() => {
    if (isCallbackPath) return
    if (!isLoading && isAuthenticated) {
      const params = new URLSearchParams(window.location.search)
      const target = params.get('redirect') || '/'
      void navigate({ to: target, replace: true })
    }
  }, [isCallbackPath, isLoading, isAuthenticated, location.search, navigate])

  // /login/callback is a child route of /login — defer to its Outlet.
  if (isCallbackPath) {
    return <Outlet />
  }

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-6">
      <div
        className="flex flex-col items-center gap-2 text-center"
        style={{ color: 'var(--fg)' }}
      >
        <TitanGlyph />
        <div
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 18,
            fontWeight: 600,
            letterSpacing: '-0.01em',
          }}
        >
          Titan
        </div>
        <p
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: 12,
            color: 'var(--fg-muted, var(--fg))',
            opacity: 0.7,
            margin: 0,
            maxWidth: '28ch',
          }}
        >
          YAML pipelines, SRE-grade UI.
        </p>
      </div>
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>Sign in to Titan</CardTitle>
          <CardDescription>
            You will be redirected to your identity provider to complete sign-in.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Button
            type="button"
            className="w-full"
            disabled={isLoading || isAuthenticated}
            onClick={() => {
              void signinRedirect()
            }}
          >
            Sign in
          </Button>
          {isAuthenticated && (
            <p className="text-sm text-muted-foreground">You are already signed in.</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
