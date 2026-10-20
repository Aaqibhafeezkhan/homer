#!/usr/bin/env node
/**
 * CRAP report for the console: per-function cyclomatic complexity (TypeScript compiler API) combined
 * with per-function statement coverage from `coverage/coverage-final.json` (`npm run test:coverage`).
 * Writes `coverage/crap/{crap-report.txt,gl-code-quality-crap.json,crap-summary.txt}`.
 *
 * Unit, complexity and coverage rules are shared with the backend's report so both stacks score
 * alike; never exits non-zero because of violations — the CI gate reads `crap-summary.txt`.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';

const CONSOLE_DIR = path.join(import.meta.dirname, '..');
const COVERAGE_FILE = path.join(CONSOLE_DIR, 'coverage', 'coverage-final.json');
const OUTPUT_DIR = path.join(CONSOLE_DIR, 'coverage', 'crap');
const DEFAULT_THRESHOLD = 30;
const STDOUT_VIOLATIONS = 20;

// Keep in sync with coverage.include/exclude in vitest.config.ts.
const SCORED_DIRS = ['app', 'components', 'lib'];
const SCORED_FILES = ['middleware.ts'];

const UNIT_KINDS = new Set([
  ts.SyntaxKind.FunctionDeclaration,
  ts.SyntaxKind.FunctionExpression,
  ts.SyntaxKind.ArrowFunction,
  ts.SyntaxKind.MethodDeclaration,
  ts.SyntaxKind.Constructor,
  ts.SyntaxKind.GetAccessor,
  ts.SyntaxKind.SetAccessor,
]);

const DECISION_KINDS = new Set([
  ts.SyntaxKind.IfStatement,
  ts.SyntaxKind.ConditionalExpression,
  ts.SyntaxKind.CaseClause,
  ts.SyntaxKind.ForStatement,
  ts.SyntaxKind.ForInStatement,
  ts.SyntaxKind.ForOfStatement,
  ts.SyntaxKind.WhileStatement,
  ts.SyntaxKind.DoStatement,
  ts.SyntaxKind.CatchClause,
]);

// The logical-assignment forms (`&&=`, `||=`, `??=`) short-circuit exactly like their operators.
const DECISION_OPERATORS = new Set([
  ts.SyntaxKind.AmpersandAmpersandToken,
  ts.SyntaxKind.BarBarToken,
  ts.SyntaxKind.QuestionQuestionToken,
  ts.SyntaxKind.AmpersandAmpersandEqualsToken,
  ts.SyntaxKind.BarBarEqualsToken,
  ts.SyntaxKind.QuestionQuestionEqualsToken,
]);

// Expressions an anonymous function can sit in without changing what holds it.
const TRANSPARENT_KINDS = new Set([
  ts.SyntaxKind.ParenthesizedExpression,
  ts.SyntaxKind.AsExpression,
  ts.SyntaxKind.SatisfiesExpression,
  ts.SyntaxKind.TypeAssertionExpression,
  ts.SyntaxKind.NonNullExpression,
]);

/** Whether a console-relative posix path is in the scored file set. */
export function isScoredPath(relPath) {
  const included =
    SCORED_FILES.includes(relPath) ||
    (SCORED_DIRS.some((dir) => relPath.startsWith(`${dir}/`)) && /\.tsx?$/.test(relPath));
  return (
    included &&
    !path.posix.basename(relPath).includes('.test.') &&
    !relPath.startsWith('components/ui/') &&
    !relPath.endsWith('.d.ts')
  );
}

/** The scored source files under `rootDir`, as sorted console-relative posix paths. */
export function collectScoredFiles(rootDir) {
  const files = SCORED_FILES.filter((file) => fs.existsSync(path.join(rootDir, file)));
  for (const dir of SCORED_DIRS) {
    const absDir = path.join(rootDir, dir);
    if (!fs.existsSync(absDir)) continue;
    for (const entry of fs.readdirSync(absDir, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile()) continue;
      files.push(path.relative(rootDir, path.join(entry.parentPath, entry.name)).split(path.sep).join('/'));
    }
  }
  return files.filter(isScoredPath).sort();
}

function isUnit(node) {
  return UNIT_KINDS.has(node.kind) && node.body !== undefined;
}

function isDecision(node) {
  return DECISION_KINDS.has(node.kind) || (ts.isBinaryExpression(node) && DECISION_OPERATORS.has(node.operatorToken.kind));
}

function complexity(unit) {
  let cc = 1;
  const visit = (node) => {
    if (isUnit(node)) return;
    if (isDecision(node)) cc += 1;
    ts.forEachChild(node, visit);
  };
  ts.forEachChild(unit, visit);
  return cc;
}

function calleeName(expression) {
  if (ts.isIdentifier(expression)) return expression.text;
  if (ts.isPropertyAccessExpression(expression)) return expression.name.text;
  return '<lambda>';
}

/** What holds an anonymous function: the variable/property/JSX prop it is assigned to, or its callee. */
function holderName(node) {
  let child = node;
  let parent = node.parent;
  while (TRANSPARENT_KINDS.has(parent.kind)) {
    child = parent;
    parent = parent.parent;
  }
  if ((ts.isVariableDeclaration(parent) || ts.isPropertyDeclaration(parent)) && parent.initializer === child) {
    return parent.name.getText();
  }
  if (ts.isPropertyAssignment(parent) && parent.initializer === child) return parent.name.getText();
  if (ts.isBinaryExpression(parent) && parent.operatorToken.kind === ts.SyntaxKind.EqualsToken && parent.right === child) {
    return ts.isPropertyAccessExpression(parent.left) ? parent.left.name.text : parent.left.getText();
  }
  if (ts.isJsxExpression(parent) && ts.isJsxAttribute(parent.parent)) return parent.parent.name.getText();
  if ((ts.isCallExpression(parent) || ts.isNewExpression(parent)) && parent.arguments?.includes(child)) {
    return calleeName(parent.expression);
  }
  return '<lambda>';
}

function unitName(node, line) {
  if (ts.isConstructorDeclaration(node)) return 'constructor';
  if (ts.isGetAccessorDeclaration(node)) return `get ${node.name.getText()}`;
  if (ts.isSetAccessorDeclaration(node)) return `set ${node.name.getText()}`;
  if (node.name) return node.name.getText();
  return `${holderName(node)}@${line}`;
}

function hasCode(unit) {
  return !ts.isBlock(unit.body) || unit.body.statements.length > 0;
}

/**
 * Every unit (function body) in a TS/TSX source: display name, 1-based first and last line, source span, cyclomatic
 * complexity (excluding nested units) and whether it holds any code. Throws on a syntax error.
 */
export function findUnits(fileName, sourceText) {
  const kind = fileName.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sourceFile = ts.createSourceFile(fileName, sourceText, ts.ScriptTarget.Latest, true, kind);
  // parseDiagnostics is internal but the only syntax-error signal short of building a Program.
  const [syntaxError] = sourceFile.parseDiagnostics ?? [];
  if (syntaxError) {
    throw new Error(`${fileName}: cannot parse: ${ts.flattenDiagnosticMessageText(syntaxError.messageText, '\n')}`);
  }
  const units = [];
  const visit = (node, chain) => {
    let childChain = chain;
    if (isUnit(node)) {
      const start = node.getStart(sourceFile);
      const line = sourceFile.getLineAndCharacterOfPosition(start).line + 1;
      const endLine = sourceFile.getLineAndCharacterOfPosition(node.end).line + 1;
      childChain = [...chain, unitName(node, line)];
      units.push({ name: childChain.join('.'), line, endLine, start, end: node.end, cc: complexity(node), hasCode: hasCode(node) });
    } else if ((ts.isClassDeclaration(node) || ts.isClassExpression(node)) && node.name) {
      childChain = [...chain, node.name.text];
    }
    ts.forEachChild(node, (child) => visit(child, childChain));
  };
  visit(sourceFile, []);
  return units;
}

function lineStarts(text) {
  const starts = [0];
  for (let i = 0; i < text.length; i += 1) if (text[i] === '\n') starts.push(i + 1);
  return starts;
}

/**
 * Per unit, the istanbul statements it owns: each statement goes to the innermost unit whose span
 * contains its start (istanbul lines are 1-based, columns 0-based). Returns `{statements, covered}`
 * aligned with `units`.
 */
export function attributeStatements(units, sourceText, fileCoverage) {
  const starts = lineStarts(sourceText);
  const owned = units.map(() => ({ statements: 0, covered: 0 }));
  for (const [id, { start }] of Object.entries(fileCoverage.statementMap)) {
    const position = (starts[start.line - 1] ?? sourceText.length) + start.column;
    let owner = -1;
    units.forEach((unit, i) => {
      const contains = unit.start <= position && position < unit.end;
      if (contains && (owner < 0 || unit.end - unit.start < units[owner].end - units[owner].start)) owner = i;
    });
    if (owner < 0) continue;
    owned[owner].statements += 1;
    if (fileCoverage.s[id] > 0) owned[owner].covered += 1;
  }
  return owned;
}

export function crapScore(cc, coverage) {
  return cc ** 2 * (1 - coverage) ** 3 + cc;
}

/**
 * Scores one file. `fileCoverage` is its istanbul entry, or undefined when the coverage report has
 * none (then every unit holding code scores with coverage 0). Returns `{rows, unscored}`.
 */
export function analyzeFile(repoPath, sourceText, fileCoverage) {
  const units = findUnits(repoPath, sourceText);
  const owned = fileCoverage
    ? attributeStatements(units, sourceText, fileCoverage)
    : units.map((unit) => ({ statements: unit.hasCode ? 1 : 0, covered: 0 }));
  const rows = [];
  let unscored = 0;
  units.forEach((unit, i) => {
    const { statements, covered } = owned[i];
    if (statements === 0) {
      unscored += 1;
      return;
    }
    const coverage = covered / statements;
    rows.push({ path: repoPath, line: unit.line, endLine: unit.endLine, start: unit.start, name: unit.name, cc: unit.cc, coverage, crap: crapScore(unit.cc, coverage) });
  });
  return { rows, unscored };
}

/** Rows over `threshold`, highest CRAP first. */
export function violations(rows, threshold) {
  return rows
    .filter((row) => row.crap > threshold)
    .sort((a, b) => b.crap - a.crap || a.path.localeCompare(b.path) || a.start - b.start);
}

export function formatReport(violating, scored, unscored, threshold) {
  const lines = [
    `CRAP report: ${violating.length} violation(s) over threshold ${threshold} (${scored} units scored, ${unscored} unscored)`,
    ...violating.map(
      (v) =>
        `${v.crap.toFixed(1).padStart(8)}  cc=${String(v.cc).padEnd(3)} cov=${(v.coverage * 100).toFixed(1).padStart(5)}%  ${v.path}:${v.line}  ${v.name}`,
    ),
  ];
  return `${lines.join('\n')}\n`;
}

function sha1(text) {
  return crypto.createHash('sha1').update(text).digest('hex');
}

/**
 * Code Quality (Code Climate) entries, one per violation. The fingerprint ignores line numbers (every `@<line>`
 * in the name is dropped) so it survives edits; same-file duplicates of a stripped name are told apart
 * by their occurrence order in the file, because Code Quality readers dedup entries by fingerprint.
 */
export function codeQuality(violating, threshold) {
  const stripped = (name) => name.replace(/@\d+(?=\.|$)/g, '');
  const occurrence = new Map();
  const seen = new Map();
  for (const v of [...violating].sort((a, b) => a.path.localeCompare(b.path) || a.start - b.start)) {
    const key = `${v.path}|${stripped(v.name)}`;
    const n = seen.get(key) ?? 0;
    seen.set(key, n + 1);
    occurrence.set(v, `${key}|${n}`);
  }
  return violating.map((v) => ({
    description: `CRAP ${v.crap.toFixed(1)} (complexity ${v.cc}, coverage ${(v.coverage * 100).toFixed(1)}%) > ${threshold} in ${v.name}`,
    check_name: 'crap',
    fingerprint: sha1(occurrence.get(v)),
    severity: v.crap > 2 * threshold ? 'major' : 'minor',
    location: { path: v.path, lines: { begin: v.line, end: v.endLine } },
  }));
}

export function formatSummary(violationCount, scored, threshold) {
  return `violations=${violationCount}\nscored=${scored}\nthreshold=${threshold}\n`;
}

const CHECKS_FILE = path.join(CONSOLE_DIR, '..', 'tools', 'quality', 'checks.json');

/** The `crap` check's threshold in tools/quality/checks.json (shared with the backend), else the default. */
export function configuredThreshold(checksJson) {
  const crap = checksJson ? JSON.parse(checksJson).checks?.find((check) => check.name === 'crap') : undefined;
  return typeof crap?.threshold === 'number' ? crap.threshold : DEFAULT_THRESHOLD;
}

/** CRAP_THRESHOLD overrides the configured threshold for a local run. */
export function parseThreshold(value, fallback = DEFAULT_THRESHOLD) {
  if (value === undefined || value === '') return fallback;
  const threshold = Number(value);
  if (!Number.isFinite(threshold) || threshold < 0) throw new Error(`CRAP_THRESHOLD must be a non-negative number, got "${value}"`);
  return threshold;
}

function readCoverage() {
  if (!fs.existsSync(COVERAGE_FILE)) throw new Error(`${COVERAGE_FILE} not found — run npm run test:coverage first.`);
  let coverage;
  try {
    coverage = JSON.parse(fs.readFileSync(COVERAGE_FILE, 'utf8'));
  } catch (error) {
    throw new Error(`${COVERAGE_FILE} is not valid JSON: ${error.message}`);
  }
  return new Map(
    Object.entries(coverage).map(([file, entry]) => [
      path.relative(CONSOLE_DIR, entry.path ?? file).split(path.sep).join('/'),
      entry,
    ]),
  );
}

function main() {
  const checks = fs.existsSync(CHECKS_FILE) ? fs.readFileSync(CHECKS_FILE, 'utf8') : undefined;
  const threshold = parseThreshold(process.env.CRAP_THRESHOLD, configuredThreshold(checks));
  const coverage = readCoverage();
  const rows = [];
  let unscored = 0;
  for (const relPath of collectScoredFiles(CONSOLE_DIR)) {
    const source = fs.readFileSync(path.join(CONSOLE_DIR, relPath), 'utf8');
    const result = analyzeFile(`console/${relPath}`, source, coverage.get(relPath));
    rows.push(...result.rows);
    unscored += result.unscored;
  }
  const violating = violations(rows, threshold);
  const report = formatReport(violating, rows.length, unscored, threshold);
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUTPUT_DIR, 'crap-report.txt'), report);
  fs.writeFileSync(path.join(OUTPUT_DIR, 'gl-code-quality-crap.json'), `${JSON.stringify(codeQuality(violating, threshold), null, 2)}\n`);
  fs.writeFileSync(path.join(OUTPUT_DIR, 'crap-summary.txt'), formatSummary(violating.length, rows.length, threshold));
  console.log(report.split('\n').slice(0, 1 + STDOUT_VIOLATIONS).join('\n'));
  if (violating.length > STDOUT_VIOLATIONS) console.log(`… ${violating.length - STDOUT_VIOLATIONS} more in ${path.join(OUTPUT_DIR, 'crap-report.txt')}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
