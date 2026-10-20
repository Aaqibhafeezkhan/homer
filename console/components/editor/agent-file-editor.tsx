'use client';

import dynamic from 'next/dynamic';
import { useEffect, useRef, type RefObject } from 'react';
import { useAgentSchema } from '@/lib/api/queries';
import { setupMonaco } from '@/lib/monaco/setup';

// Must run synchronously at module load, before @monaco-editor/react's
// dynamic loader fires on first <Editor> mount. useEffect is too late —
// by then Monaco has already been fetched from the default CDN, and
// monaco-yaml (imported from npm) cannot contribute to that separate
// instance. This is a no-op on the server.
setupMonaco();

const MonacoEditor = dynamic(() => import('@monaco-editor/react'), {
  ssr: false,
  loading: () => (
    <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
      Loading editor...
    </div>
  ),
});

interface AgentFileEditorProps {
  path: string;
  value: string;
  onChange: (value: string) => void;
  readOnly?: boolean;
}

// Monaco language per file extension, checked in order.
const LANGUAGE_BY_EXTENSION: ReadonlyArray<[string[], string]> = [
  [['.json'], 'json'],
  [['.yaml', '.yml'], 'yaml'],
  [['.md'], 'markdown'],
  [['.sh', '.bash'], 'shell'],
  [['.js', '.mjs'], 'javascript'],
  [['.ts'], 'typescript'],
  [['.py'], 'python'],
];

function languageFor(path: string): string {
  const lower = path.toLowerCase();
  const match = LANGUAGE_BY_EXTENSION.find(([extensions]) =>
    extensions.some((extension) => lower.endsWith(extension)),
  );
  return match ? match[1] : 'plaintext';
}

const AGENT_SCHEMA_URI = 'https://homer.local/schemas/agent.json';

type MonacoModule = typeof import('monaco-editor');
type YamlHandle = Awaited<
  ReturnType<typeof import('monaco-yaml').configureMonacoYaml>
>;

function applyJsonSchema(monaco: MonacoModule, schema: object): void {
  const jsonLang = (
    monaco.languages as unknown as {
      json: {
        jsonDefaults: {
          setDiagnosticsOptions: (opts: {
            validate: boolean;
            schemas: Array<{
              uri: string;
              fileMatch: string[];
              schema: object;
            }>;
          }) => void;
        };
      };
    }
  ).json;
  jsonLang.jsonDefaults.setDiagnosticsOptions({
    validate: true,
    schemas: [
      {
        uri: AGENT_SCHEMA_URI,
        fileMatch: ['*agent.json'],
        schema,
      },
    ],
  });
}

// Configures monaco-yaml once, then updates that handle's options on later calls.
async function applyYamlSchema(
  monaco: MonacoModule,
  schema: object,
  yamlHandleRef: RefObject<YamlHandle | null>,
): Promise<void> {
  const opts = {
    enableSchemaRequest: false,
    validate: true,
    hover: true,
    completion: true,
    schemas: [
      {
        uri: AGENT_SCHEMA_URI,
        fileMatch: ['*agent.yaml', '*agent.yml'],
        schema,
      },
    ],
  };
  if (yamlHandleRef.current) {
    await yamlHandleRef.current.update(opts);
  } else {
    const { configureMonacoYaml } = await import('monaco-yaml');
    yamlHandleRef.current = configureMonacoYaml(monaco, opts);
  }
}

/**
 * Validates an agent definition file against the backend's agent schema once
 * Monaco and the schema are both loaded. Returns the ref the editor's
 * beforeMount fills with the Monaco module.
 */
function useAgentSchemaValidation(path: string, language: string) {
  const { data: schema } = useAgentSchema();
  const isAgentDef =
    path === 'agent.json' || path === 'agent.yaml' || path === 'agent.yml';
  const monacoRef = useRef<MonacoModule | null>(null);
  const yamlHandleRef = useRef<YamlHandle | null>(null);

  useEffect(() => {
    const monaco = monacoRef.current;
    if (!monaco || !isAgentDef || !schema) return;

    if (language === 'json') {
      applyJsonSchema(monaco, schema as object);
      return;
    }

    if (language === 'yaml') {
      void applyYamlSchema(monaco, schema as object, yamlHandleRef);
    }
  }, [schema, language, isAgentDef]);

  useEffect(() => {
    return () => {
      yamlHandleRef.current?.dispose();
      yamlHandleRef.current = null;
    };
  }, []);

  return monacoRef;
}

export function AgentFileEditor({
  path,
  value,
  onChange,
  readOnly = false,
}: AgentFileEditorProps) {
  const language = languageFor(path);
  const monacoRef = useAgentSchemaValidation(path, language);

  return (
    <MonacoEditor
      path={path}
      language={language}
      value={value}
      onChange={(v) => onChange(v ?? '')}
      beforeMount={(monaco) => {
        monacoRef.current = monaco;
      }}
      theme="vs-dark"
      options={{
        readOnly,
        minimap: { enabled: false },
        fontSize: 13,
        scrollBeyondLastLine: false,
        tabSize: 2,
        automaticLayout: true,
      }}
      height="100%"
    />
  );
}
