import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { labelFromUrl } from '@/lib/instances/url';

// The warning and checkbox for an endpoint seeded from the page link rather than a saved instance.
function TrustConfirmation({
  endpoint,
  bearerMode,
  trustConfirmed,
  onTrustConfirmedChange,
}: {
  endpoint: string;
  bearerMode: boolean;
  trustConfirmed: boolean;
  onTrustConfirmedChange: (checked: boolean) => void;
}) {
  return (
    <>
      <p role="alert" className="text-sm text-destructive">
        This endpoint came from the page link and is not one of your saved
        instances. Confirm you trust <strong>{labelFromUrl(endpoint) || endpoint}</strong>{' '}
        before entering your {bearerMode ? 'token' : 'password'} — credentials are sent to it.
      </p>
      <label className="flex items-center gap-2 text-sm text-destructive">
        <input
          type="checkbox"
          checked={trustConfirmed}
          onChange={(e) => onTrustConfirmedChange(e.target.checked)}
        />
        I trust this endpoint and want to send my{' '}
        {bearerMode ? 'token' : 'credentials'} to it
      </label>
    </>
  );
}

// The folded endpoint: its label and a Change affordance that unfolds the input.
function EndpointSummary({ endpoint, onShow }: { endpoint: string; onShow: () => void }) {
  return (
    <button
      type="button"
      onClick={onShow}
      aria-label="Change instance endpoint"
      className="flex w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-left text-sm hover:bg-accent"
    >
      <span className="truncate text-muted-foreground">
        {labelFromUrl(endpoint) || 'Default instance'}
      </span>
      <span className="ml-2 shrink-0 text-xs text-muted-foreground">Change</span>
    </button>
  );
}

interface EndpointFieldProps {
  visible: boolean;
  endpoint: string;
  onEndpointChange: (value: string) => void;
  onShow: () => void;
  requiresTrustConfirmation: boolean;
  trustConfirmed: boolean;
  onTrustConfirmedChange: (checked: boolean) => void;
  bearerMode: boolean;
  mixed: boolean;
}

/**
 * The login form's instance endpoint: folded behind a summary button until
 * `visible`, then an input with the trust confirmation for a link-seeded
 * endpoint and the mixed-content warning.
 */
export function EndpointField({
  visible,
  endpoint,
  onEndpointChange,
  onShow,
  requiresTrustConfirmation,
  trustConfirmed,
  onTrustConfirmedChange,
  bearerMode,
  mixed,
}: EndpointFieldProps) {
  return (
    <div className="space-y-2">
      <Label htmlFor="endpoint">Instance endpoint</Label>
      {visible ? (
        <>
          <Input
            id="endpoint"
            type="text"
            inputMode="url"
            autoComplete="off"
            placeholder="https://homer.example.com"
            value={endpoint}
            onChange={(e) => onEndpointChange(e.target.value)}
            required
          />
          {requiresTrustConfirmation && (
            <TrustConfirmation
              endpoint={endpoint}
              bearerMode={bearerMode}
              trustConfirmed={trustConfirmed}
              onTrustConfirmedChange={onTrustConfirmedChange}
            />
          )}
          {mixed && (
            <p role="alert" className="text-sm text-destructive">
              This console is served over HTTPS and cannot call an http:// backend
              (the browser blocks mixed content). Use an https:// endpoint.
            </p>
          )}
        </>
      ) : (
        <EndpointSummary endpoint={endpoint} onShow={onShow} />
      )}
    </div>
  );
}
