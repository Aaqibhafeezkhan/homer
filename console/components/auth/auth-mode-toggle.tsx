import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import type { InstanceMode } from '@/lib/instances/store';

/** The login form's Password (cookie) / Token (bearer) radio pair. */
export function AuthModeToggle({
  mode,
  onModeChange,
}: {
  mode: InstanceMode;
  onModeChange: (mode: InstanceMode) => void;
}) {
  return (
    <div className="space-y-2">
      <Label>Authentication</Label>
      <div className="flex gap-2" role="radiogroup" aria-label="Authentication mode">
        <Button
          type="button"
          className="flex-1"
          variant={mode === 'cookie' ? 'default' : 'outline'}
          role="radio"
          aria-checked={mode === 'cookie'}
          onClick={() => onModeChange('cookie')}
        >
          Password
        </Button>
        <Button
          type="button"
          className="flex-1"
          variant={mode === 'bearer' ? 'default' : 'outline'}
          role="radio"
          aria-checked={mode === 'bearer'}
          onClick={() => onModeChange('bearer')}
        >
          Token
        </Button>
      </div>
    </div>
  );
}
