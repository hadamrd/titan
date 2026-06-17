import { Sparkles } from 'lucide-react'
import { Button } from '@/components/ui/Button'

export function AppearanceSection() {
  return (
    <div className="setting-row">
      <div>
        <div className="setting-label">Theme & density</div>
        <div className="setting-desc">
          Theme, density, mono font, and reduced-motion preferences live in the
          Tweaks panel (top-right toolbar). Settings are saved per-device.
        </div>
      </div>
      <div className="setting-control">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => {
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('titan:open-tweaks'))
            }
          }}
          data-testid="open-tweaks"
        >
          <Sparkles size={12} aria-hidden /> Open appearance settings
        </Button>
      </div>
    </div>
  )
}
