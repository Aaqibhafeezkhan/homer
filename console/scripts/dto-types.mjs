#!/usr/bin/env node
/**
 * Console side of the `dto-contract` check (ADR-0082): the shape of every interface and type alias in
 * `types/homer.ts`, the hand-written mirror of the backend DTOs, read with the TypeScript compiler API.
 * Writes `reports/dto-contract/console-dtos.json`; tools/quality/dto-contract.mjs compares it with the
 * backend's export.
 *
 * A type is coarse: string, number, boolean, unknown, a string-literal union (`enum`), `list`, `map`,
 * a `ref` to a declaration by name, an inline `object`, or a `union` of those; `nullable` marks a
 * union with `null`.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import ts from 'typescript';

const CONSOLE_DIR = path.join(import.meta.dirname, '..');
const SOURCE = path.join(CONSOLE_DIR, 'types', 'homer.ts');
const OUTPUT = path.join(CONSOLE_DIR, 'reports', 'dto-contract', 'console-dtos.json');

const KEYWORDS = {
  [ts.SyntaxKind.StringKeyword]: 'string',
  [ts.SyntaxKind.NumberKeyword]: 'number',
  [ts.SyntaxKind.BooleanKeyword]: 'boolean',
  [ts.SyntaxKind.UnknownKeyword]: 'unknown',
  [ts.SyntaxKind.AnyKeyword]: 'unknown',
};

const isNullish = (node) =>
  node.kind === ts.SyntaxKind.UndefinedKeyword ||
  (ts.isLiteralTypeNode(node) && node.literal.kind === ts.SyntaxKind.NullKeyword);

const isUndefined = (node) => node.kind === ts.SyntaxKind.UndefinedKeyword;

/** The coarse type of a type node; `file` names the source in an error. */
function typeOf(node, file) {
  if (KEYWORDS[node.kind]) return { kind: KEYWORDS[node.kind] };
  if (ts.isParenthesizedTypeNode(node)) return typeOf(node.type, file);
  if (ts.isTypeOperatorNode(node)) return typeOf(node.type, file);
  if (ts.isLiteralTypeNode(node) && ts.isStringLiteral(node.literal)) return { kind: 'enum', values: [node.literal.text] };
  if (ts.isArrayTypeNode(node)) return { kind: 'list', of: typeOf(node.elementType, file) };
  if (ts.isUnionTypeNode(node)) return unionOf(node.types, file);
  if (ts.isTypeLiteralNode(node)) return objectOf(node.members, file);
  if (ts.isTypeReferenceNode(node)) return referenceOf(node, file);
  throw new Error(`${file}: unsupported type \`${node.getText()}\``);
}

function unionOf(types, file) {
  const members = types.filter((t) => !isNullish(t)).map((t) => typeOf(t, file));
  const nullable = types.some((t) => !isUndefined(t) && isNullish(t)) ? { nullable: true } : {};
  if (members.length > 0 && members.every((m) => m.kind === 'enum')) {
    return { kind: 'enum', values: members.flatMap((m) => m.values), ...nullable };
  }
  return members.length === 1 ? { ...members[0], ...nullable } : { kind: 'union', members, ...nullable };
}

function referenceOf(node, file) {
  const name = node.typeName.getText();
  const args = node.typeArguments ?? [];
  if (name === 'Array' && args.length === 1) return { kind: 'list', of: typeOf(args[0], file) };
  if (name === 'Record' && args.length === 2) return { kind: 'map', of: typeOf(args[1], file) };
  if (args.length > 0) throw new Error(`${file}: unsupported generic type \`${node.getText()}\``);
  return { kind: 'ref', name };
}

function objectOf(members, file) {
  const indexes = members.filter(ts.isIndexSignatureDeclaration);
  if (indexes.length > 1) throw new Error(`${file}: multiple index signatures`);
  if (indexes.length === 1) {
    if (members.length > 1) throw new Error(`${file}: mixes fields with an index signature`);
    return { kind: 'map', of: typeOf(indexes[0].type, file) };
  }
  return { kind: 'object', fields: members.map((m) => fieldOf(m, file)) };
}

function fieldOf(member, file) {
  if (!ts.isPropertySignature(member) || !member.type) {
    throw new Error(`${file}: unsupported member \`${member.getText()}\``);
  }
  const source = member.getSourceFile();
  const types = ts.isUnionTypeNode(member.type) ? member.type.types : [member.type];
  return {
    name: member.name.getText(source).replace(/^['"]|['"]$/g, ''),
    line: source.getLineAndCharacterOfPosition(member.name.getStart(source)).line + 1,
    // `x: T | undefined` may be absent just like `x?: T`.
    optional: Boolean(member.questionToken) || types.some(isUndefined),
    type: typeOf(member.type, file),
  };
}

/** Every top-level interface and type alias of a TS source, by name. */
export function extractDeclarations(sourceText, file = 'homer.ts') {
  const source = ts.createSourceFile(file, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const declarations = {};
  for (const statement of source.statements) {
    if (!ts.isInterfaceDeclaration(statement) && !ts.isTypeAliasDeclaration(statement)) continue;
    const name = statement.name.text;
    const line = source.getLineAndCharacterOfPosition(statement.name.getStart(source)).line + 1;
    if (statement.typeParameters) throw new Error(`${file}:${line}: generic declaration ${name} is not supported`);
    if (ts.isInterfaceDeclaration(statement)) {
      if (statement.heritageClauses) throw new Error(`${file}:${line}: interface ${name} extends another; inline its fields`);
      declarations[name] = { line, ...objectOf(statement.members, file) };
    } else {
      declarations[name] = { line, ...typeOf(statement.type, file) };
    }
  }
  return declarations;
}

function main() {
  const declarations = extractDeclarations(fs.readFileSync(SOURCE, 'utf8'), 'console/types/homer.ts');
  fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
  fs.writeFileSync(OUTPUT, `${JSON.stringify({ file: 'console/types/homer.ts', declarations }, null, 2)}\n`);
  console.log(`dto-types: ${Object.keys(declarations).length} declaration(s) → ${path.relative(CONSOLE_DIR, OUTPUT)}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
