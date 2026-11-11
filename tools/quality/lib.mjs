/**
 * Shared output contract of the quality reports (ADR-0082): every check writes
 * `<check>-summary.txt`, `<check>-report.txt` and `gl-code-quality-<check>.json` into its own
 * directory, and never exits non-zero because of findings. Also the home of helpers more than one
 * check needs: regular-expression escaping, the mutation score, and a minimal Kotlin source lexer.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const sha1 = (value) => crypto.createHash('sha1').update(value).digest('hex');

/** `findings` → Code Quality (Code Climate) entries; a fingerprint survives the finding moving within its file. */
export function codeQuality(check, findings) {
  const seen = new Map();
  return findings.map((f) => {
    const key = `${f.path}|${f.stableKey ?? f.message}`;
    const occurrence = seen.get(key) ?? 0;
    seen.set(key, occurrence + 1);
    return {
      description: f.message,
      check_name: check,
      // The check name keeps two checks' findings at one spot apart in the merged report.
      fingerprint: sha1(`${check}|${key}|${occurrence}`),
      severity: f.severity ?? 'minor',
      location: { path: f.path, lines: { begin: f.line } },
    };
  });
}

export function formatReport(title, findings) {
  const lines = [`${title}: ${findings.length} finding(s)`];
  for (const f of findings) lines.push(`${f.path}:${f.line}  ${f.message}`);
  return `${lines.join('\n')}\n`;
}

export function formatSummary(count, extra = {}) {
  const lines = [`findings=${count}`, ...Object.entries(extra).map(([k, v]) => `${k}=${v}`)];
  return `${lines.join('\n')}\n`;
}

/** Writes the three files of `check` into `dir` and returns the report text. */
export function writeCheck(dir, check, title, findings, extra = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const report = formatReport(title, findings);
  fs.writeFileSync(path.join(dir, `${check}-report.txt`), report);
  fs.writeFileSync(path.join(dir, `${check}-summary.txt`), formatSummary(findings.length, extra));
  fs.writeFileSync(path.join(dir, `gl-code-quality-${check}.json`), `${JSON.stringify(codeQuality(check, findings), null, 2)}\n`);
  return report;
}

/**
 * Mutation score of both mutation reports (Stryker in console/scripts/mutation.mjs, PIT in pit.mjs):
 * detected (killed or timed out) / (detected + survived + uncovered), one decimal; 'n/a' with no
 * scored mutant. One definition, so the two job summaries stay comparable.
 */
export function mutationScore({ killed, timeout, survived, noCoverage }) {
  const detected = killed + timeout;
  const valid = detected + survived + noCoverage;
  return valid === 0 ? 'n/a' : ((100 * detected) / valid).toFixed(1);
}

export function parseSummary(text) {
  return Object.fromEntries(
    text
      .split('\n')
      .filter((line) => line.includes('='))
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
  );
}

/** Repo-relative POSIX path of `file` (absolute or relative to `root`). */
export const repoRelative = (root, file) => path.relative(root, path.resolve(root, file)).split(path.sep).join('/');

/** `text` safe inside a `RegExp` as a literal: every metacharacter escaped. */
export const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const quoteAt = (source, i) => (source.startsWith('"""', i) ? '"""' : source[i] === '"' || source[i] === "'" ? source[i] : null);

// One step inside a string or char literal; returns the index of the step's last character.
function stepInLiteral(source, i, stack) {
  const { quote } = stack.at(-1);
  if (source[i] === '\\' && quote !== '"""') return i + 1;
  if (quote !== "'" && source.startsWith('${', i)) {
    stack.push({ braces: 0 });
    return i + 1;
  }
  if (!source.startsWith(quote, i)) return i;
  stack.pop();
  // A raw string closes at the last three of a run of quotes: `""""` is a quote, then the end.
  let end = i + quote.length - 1;
  while (quote === '"""' && source[end + 1] === '"') end++;
  return end;
}

// One step in code or in a `${…}` template expression; returns the index of the step's last character.
function stepInCode(source, i, stack) {
  // A backticked name (`fun \`it's fine\`()`) may hold quotes and comment openers: skip it whole.
  if (source[i] === '`') {
    const close = source.indexOf('`', i + 1);
    if (close >= 0) return close;
    stack.push({ quote: '`' }); // never closed: leave the source unclean, so the fail-safe applies
    return source.length;
  }
  const quote = quoteAt(source, i);
  if (quote) {
    stack.push({ quote });
    return i + quote.length - 1;
  }
  const template = stack.at(-1);
  if (template && source[i] === '{') template.braces++;
  else if (template && source[i] === '}') {
    if (template.braces === 0) stack.pop();
    else template.braces--;
  }
  return i;
}

/**
 * The lines of a Kotlin source holding anything outside a comment. Lexes just enough for that:
 * block comments nest; a comment opener inside a string or char literal is text; a `${…}` template
 * is code again, its own literals included. A source this lexer cannot read to a clean end (an
 * unclosed literal or comment, which valid Kotlin never has) counts as code on every line.
 */
export function codeLineNumbers(source) {
  const code = new Set();
  const stack = [];
  let line = 1;
  let depth = 0;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === '\n') {
      line++;
    } else if (depth > 0) {
      const marker = source.slice(i, i + 2);
      if (marker === '/*' || marker === '*/') [depth, i] = [depth + (marker === '/*' ? 1 : -1), i + 1];
    } else if (stack.at(-1)?.quote) {
      code.add(line);
      i = stepInLiteral(source, i, stack);
    } else if (source.startsWith('//', i)) {
      while (i + 1 < source.length && source[i + 1] !== '\n') i++;
    } else if (source.startsWith('/*', i)) {
      [depth, i] = [1, i + 1];
    } else if (!/\s/.test(c)) {
      code.add(line);
      i = stepInCode(source, i, stack);
    }
  }
  if (stack.length > 0 || depth > 0) return new Set(source.split('\n').map((_, n) => n + 1));
  return code;
}

/**
 * `source` with every comment and string or char literal blanked to a space, so a scan of the rest
 * reads code only: no `fun` declaration example in a KDoc, no parenthesis inside a default value.
 * The same lexer as `codeLineNumbers`; a source it cannot read to a clean end comes back unchanged,
 * all of it code, as there `codeLineNumbers` counts every line.
 */
export function codeText(source) {
  let out = '';
  const stack = [];
  let depth = 0;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === '\n') out += c;
    else if (depth > 0) {
      out += ' ';
      const marker = source.slice(i, i + 2);
      if (marker === '/*' || marker === '*/') [depth, i] = [depth + (marker === '/*' ? 1 : -1), i + 1];
    } else if (stack.at(-1)?.quote) {
      i = stepInLiteral(source, i, stack);
      out += ' ';
    } else if (source.startsWith('//', i)) {
      while (i + 1 < source.length && source[i + 1] !== '\n') i++;
      out += ' ';
    } else if (source.startsWith('/*', i)) {
      [depth, i] = [1, i + 1];
      out += ' ';
    } else if (quoteAt(source, i)) {
      i = stepInCode(source, i, stack); // the literal opens here: its quote is not code either
      out += ' ';
    } else {
      out += c;
      i = stepInCode(source, i, stack);
    }
  }
  return stack.length > 0 || depth > 0 ? source : out;
}

/** Runs `main` when the module is the entry point; a thrown error is a real failure (exit 1). */
export function runMain(meta, main) {
  if (!process.argv[1] || meta.url !== pathToFileURL(path.resolve(process.argv[1])).href) return;
  try {
    const output = main(process.argv.slice(2));
    if (output) process.stdout.write(output);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
