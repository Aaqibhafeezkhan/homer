import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AgentFileEditor } from './agent-file-editor';
import { setupMonaco } from '@/lib/monaco/setup';

interface FakeEditorProps {
  path: string;
  language: string;
  value: string;
  onChange: (value: string | undefined) => void;
  beforeMount: (monaco: unknown) => void;
  options: { readOnly: boolean };
}

const editor = vi.hoisted(() => ({ props: null as FakeEditorProps | null }));
const useAgentSchemaMock = vi.hoisted(() => vi.fn());
const setDiagnosticsOptions = vi.hoisted(() => vi.fn());
const yamlHandle = vi.hoisted(() => ({
  update: vi.fn(async () => {}),
  dispose: vi.fn(),
}));
const configureMonacoYaml = vi.hoisted(() => vi.fn(() => yamlHandle));
const fakeMonaco = vi.hoisted(() => ({
  languages: { json: { jsonDefaults: { setDiagnosticsOptions } } },
}));

// monaco-editor cannot run under jsdom: stand in for the dynamically loaded
// editor with one that records its props and hands beforeMount a fake Monaco.
vi.mock('next/dynamic', () => ({
  default: () =>
    function FakeMonacoEditor(props: FakeEditorProps) {
      editor.props = props;
      props.beforeMount(fakeMonaco);
      return (
        <div
          data-testid="monaco"
          data-path={props.path}
          data-language={props.language}
          data-readonly={String(props.options.readOnly)}
        >
          {props.value}
        </div>
      );
    },
}));
vi.mock('@/lib/monaco/setup', () => ({ setupMonaco: vi.fn() }));
vi.mock('@/lib/api/queries', () => ({ useAgentSchema: useAgentSchemaMock }));
vi.mock('monaco-yaml', () => ({ configureMonacoYaml }));

const AGENT_SCHEMA_URI = 'https://homer.local/schemas/agent.json';
const schema = { type: 'object', title: 'agent' };

function renderEditor(path: string, extra: Partial<{ readOnly: boolean; onChange: (v: string) => void }> = {}) {
  return render(
    <AgentFileEditor path={path} value="content" onChange={extra.onChange ?? vi.fn()} readOnly={extra.readOnly} />,
  );
}

describe('AgentFileEditor', () => {
  beforeEach(() => {
    editor.props = null;
    useAgentSchemaMock.mockReturnValue({ data: schema });
    setDiagnosticsOptions.mockClear();
    configureMonacoYaml.mockClear();
    yamlHandle.update.mockClear();
    yamlHandle.dispose.mockClear();
  });

  it('sets Monaco up at module load', () => {
    expect(setupMonaco).toHaveBeenCalled();
  });

  it.each([
    ['config.json', 'json'],
    ['CONFIG.JSON', 'json'],
    ['agent.yaml', 'yaml'],
    ['nested/agent.yml', 'yaml'],
    ['README.md', 'markdown'],
    ['run.sh', 'shell'],
    ['run.bash', 'shell'],
    ['index.js', 'javascript'],
    ['index.mjs', 'javascript'],
    ['index.ts', 'typescript'],
    ['main.py', 'python'],
    ['notes.txt', 'plaintext'],
    ['Makefile', 'plaintext'],
  ])('opens %s with the %s language', (path, language) => {
    renderEditor(path);
    expect(screen.getByTestId('monaco')).toHaveAttribute('data-language', language);
  });

  it('passes path, value and readOnly through to the editor', () => {
    renderEditor('scripts/run.sh', { readOnly: true });
    const el = screen.getByTestId('monaco');
    expect(el).toHaveAttribute('data-path', 'scripts/run.sh');
    expect(el).toHaveAttribute('data-readonly', 'true');
    expect(el).toHaveTextContent('content');
  });

  it('is editable by default', () => {
    renderEditor('run.sh');
    expect(screen.getByTestId('monaco')).toHaveAttribute('data-readonly', 'false');
  });

  it('reports edits, mapping a cleared buffer to an empty string', () => {
    const onChange = vi.fn();
    renderEditor('run.sh', { onChange });
    editor.props!.onChange('echo hi');
    editor.props!.onChange(undefined);
    expect(onChange).toHaveBeenNthCalledWith(1, 'echo hi');
    expect(onChange).toHaveBeenNthCalledWith(2, '');
  });

  it('registers the agent schema for JSON validation of agent.json', () => {
    renderEditor('agent.json');
    expect(setDiagnosticsOptions).toHaveBeenCalledWith({
      validate: true,
      schemas: [{ uri: AGENT_SCHEMA_URI, fileMatch: ['*agent.json'], schema }],
    });
    expect(configureMonacoYaml).not.toHaveBeenCalled();
  });

  it.each(['agent.yaml', 'agent.yml'])('configures monaco-yaml with the agent schema for %s', async (path) => {
    renderEditor(path);
    await waitFor(() => expect(configureMonacoYaml).toHaveBeenCalledTimes(1));
    expect(configureMonacoYaml).toHaveBeenCalledWith(fakeMonaco, {
      enableSchemaRequest: false,
      validate: true,
      hover: true,
      completion: true,
      schemas: [{ uri: AGENT_SCHEMA_URI, fileMatch: ['*agent.yaml', '*agent.yml'], schema }],
    });
    expect(setDiagnosticsOptions).not.toHaveBeenCalled();
  });

  it('updates the existing monaco-yaml handle when the schema changes, and disposes it on unmount', async () => {
    const { rerender, unmount } = renderEditor('agent.yaml');
    await waitFor(() => expect(configureMonacoYaml).toHaveBeenCalledTimes(1));

    const nextSchema = { type: 'object', title: 'agent v2' };
    useAgentSchemaMock.mockReturnValue({ data: nextSchema });
    rerender(<AgentFileEditor path="agent.yaml" value="content" onChange={vi.fn()} />);

    await waitFor(() => expect(yamlHandle.update).toHaveBeenCalledTimes(1));
    expect(yamlHandle.update).toHaveBeenCalledWith(
      expect.objectContaining({
        schemas: [{ uri: AGENT_SCHEMA_URI, fileMatch: ['*agent.yaml', '*agent.yml'], schema: nextSchema }],
      }),
    );
    expect(configureMonacoYaml).toHaveBeenCalledTimes(1);

    unmount();
    expect(yamlHandle.dispose).toHaveBeenCalledTimes(1);
  });

  it.each(['config.json', 'other.yaml', 'scripts/agent.json'])(
    'applies no schema to %s, which is not the agent definition',
    async (path) => {
      renderEditor(path);
      await Promise.resolve();
      expect(setDiagnosticsOptions).not.toHaveBeenCalled();
      expect(configureMonacoYaml).not.toHaveBeenCalled();
    },
  );

  it('applies no schema before it has loaded', async () => {
    useAgentSchemaMock.mockReturnValue({ data: undefined });
    renderEditor('agent.yaml');
    renderEditor('agent.json');
    await Promise.resolve();
    expect(setDiagnosticsOptions).not.toHaveBeenCalled();
    expect(configureMonacoYaml).not.toHaveBeenCalled();
  });

  it('does not dispose anything on unmount when monaco-yaml was never configured', () => {
    const { unmount } = renderEditor('agent.json');
    unmount();
    expect(yamlHandle.dispose).not.toHaveBeenCalled();
  });
});
