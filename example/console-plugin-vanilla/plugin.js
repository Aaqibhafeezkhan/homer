// A console plugin with no framework and no build step: plain DOM, plain ES
// module. The console only requires a default export with `mount`, so React is
// a choice the plugin makes, not part of the contract.
//
// This file is what the console imports — there is no build output. See
// README.md, and console/lib/plugins/types.ts for the authoritative contract.

/** Console theme tokens are plain CSS vars, so a plugin inherits the active theme. */
const CSS = `
  .vp-card {
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius);
    background: hsl(var(--card));
    color: hsl(var(--card-foreground));
    padding: 1rem;
    margin-bottom: 1rem;
  }
  .vp-muted { color: hsl(var(--muted-foreground)); font-size: .875rem; }
  .vp-err { color: hsl(var(--destructive)); }
  .vp-btn {
    border: 1px solid hsl(var(--border));
    border-radius: var(--radius);
    background: hsl(var(--primary));
    color: hsl(var(--primary-foreground));
    padding: .5rem .75rem;
    cursor: pointer;
  }
  .vp-table { width: 100%; border-collapse: collapse; }
  .vp-table th, .vp-table td {
    text-align: left;
    padding: .375rem .5rem;
    border-bottom: 1px solid hsl(var(--border));
  }
  .vp-table th { color: hsl(var(--muted-foreground)); font-weight: 500; }
`;

/**
 * Every value from the API lands via textContent, never innerHTML. Agent
 * descriptions are free text written by whoever authored the agent, so treating
 * them as markup would make this example an injection sink.
 */
function el(tag, opts = {}, children = []) {
  const node = document.createElement(tag);
  if (opts.className) node.className = opts.className;
  if (opts.text !== undefined) node.textContent = opts.text;
  for (const child of children) node.append(child);
  return node;
}

function agentTable(agents) {
  const head = el('tr', {}, [el('th', { text: 'agent' }), el('th', { text: 'description' })]);
  const rows = agents.map((a) =>
    el('tr', {}, [el('td', { text: a.name }), el('td', { text: a.description ?? '—' })]),
  );
  return el('table', { className: 'vp-table' }, [
    el('thead', {}, [head]),
    el('tbody', {}, rows),
  ]);
}

export default {
  route: '/pluginhtml',
  title: 'Plain HTML',

  mount(host, ctx) {
    const style = el('style', { text: CSS });

    const info = el('div', { className: 'vp-card' }, [
      el('p', {
        className: 'vp-muted',
        text: 'Runtime plugin with no framework and no build step — plain DOM, plain ES module.',
      }),
      el('p', {
        className: 'vp-muted',
        text: `user: ${ctx.user?.username ?? 'anonymous'} · role: ${ctx.user?.role ?? 'n/a'} · theme: ${ctx.theme ?? 'unknown'}`,
      }),
    ]);

    const body = el('div', { className: 'vp-card' });
    const button = el('button', { className: 'vp-btn', text: 'Reload' });
    button.type = 'button';

    const render = (children) => body.replaceChildren(...children);

    const load = async () => {
      render([el('p', { className: 'vp-muted', text: 'Loading...' })]);
      try {
        const [{ agents }, processes] = await Promise.all([
          ctx.api.getAgents(),
          ctx.api.getProcesses({ limit: 5 }),
        ]);
        render([
          el('h3', { text: 'Homer API through the host client' }),
          el('p', { text: `processes: ${processes.total ?? processes.processes.length}` }),
          agentTable(agents),
        ]);
      } catch (e) {
        render([
          el('p', { className: 'vp-err', text: `API error: ${e instanceof Error ? e.message : String(e)}` }),
        ]);
      }
    };

    button.addEventListener('click', load);
    host.append(style, el('h2', { text: 'Plain HTML' }), info, body, button);
    void load();

    // The console clears the host element on unmount, so only the listener
    // needs releasing here.
    return () => button.removeEventListener('click', load);
  },
};
