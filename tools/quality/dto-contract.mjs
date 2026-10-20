/**
 * dto-contract: drift between the backend DTOs and their hand-written TS mirrors in
 * console/types/homer.ts (ADR-0082). Compares the backend export (`DtoContractTest`, written by
 * `:app:test`) with the console extract (`console/scripts/dto-types.mjs`, run by
 * `npm run quality:report`).
 * Usage (repository root): node tools/quality/dto-contract.mjs [backend json] [console json] [out dir]
 *
 * A TS declaration is matched to the Kotlin class of the same simple name, or of its RENAMES entry.
 * Every TS declaration needs a Kotlin counterpart or a CONSOLE_ONLY reason, and every exported Kotlin
 * class must be mirrored somewhere, so neither side drops out of the comparison silently.
 *
 * The backend encodes with `encodeDefaults` and explicit nulls, so a response carries every field:
 * there only a Kotlin-nullable field the TS type does not allow `null` for matters. A request is the
 * other way round: TS must not leave out, or send `null` for, a field Kotlin requires.
 */
import fs from 'node:fs';
import { runMain, writeCheck } from './lib.mjs';

const CHECK = 'dto-contract';

/** TS declaration → Kotlin DTO, where the console names the type differently. */
export const RENAMES = {
  ProcessInfo: 'ProcessInfoResponse',
  ProcessExecution: 'ExecutionInfoResponse',
  RunnerState: 'RunnerStateResponse',
  PodStartupFailure: 'PodStartupFailureResponse',
  SweepDeletedPod: 'SweepDeletedPodResponse',
  SweepStatus: 'SweepStatusResponse',
  Question: 'QuestionResponse',
  Continuation: 'ContinuationResponse',
  ContinuationsListResponse: 'ContinuationListResponse',
  AgentInput: 'Param',
  AgentParameterTransformer: 'Transformer',
  AgentCronInfo: 'CronInfo',
  AgentFileEntry: 'AgentFileEntryResponse',
  AgentExecutionResponse: 'ExecuteResponse',
  LlmTurnStatus: 'TurnStatus',
};

/** TS declarations with no backend DTO of their own, and why. */
export const CONSOLE_ONLY = {
  ProcessCostSource: "CostSource plus 'mixed'; ProcessInfoResponse.costSource is a String",
  AgentQueryParam: 'deprecated alias of AgentInput',
  ProcessFilters: 'query parameters of GET /api/v1/processes, not a body',
  Role: 'MeResponse.role is a String the console narrows',
  LlmStreamFrame: 'the llm.event frame is built with buildJsonObject in StreamingRoutes.kt',
};

/** Kotlin fields the console deliberately does not mirror (`Class.field`), and why. */
export const UNMIRRORED_FIELDS = {
  'LangGraphGraphInfo.mermaid': 'deprecated (ADR-0073); the console draws the topology',
  'LangGraphRunStatus.mermaid': 'deprecated (ADR-0073); the console draws the topology',
};

function describeKotlin(type) {
  if (type.kind === 'list' || type.kind === 'map') return `${type.kind} of ${describeKotlin(type.of)}`;
  return type.class ? `${type.kind} ${type.class}` : type.kind;
}

function describeTs(type) {
  if (type.kind === 'list' || type.kind === 'map') return `${type.kind} of ${describeTs(type.of)}`;
  if (type.kind === 'ref') return type.name;
  if (type.kind === 'enum') return type.values.map((v) => `'${v}'`).join(' | ');
  return type.kind;
}

class Comparison {
  constructor(backend, consoleTypes, tables) {
    this.unmirrored = tables.unmirrored;
    this.kotlinToTs = Object.fromEntries(Object.entries(tables.renames).map(([ts, kotlin]) => [kotlin, ts]));
    this.classes = backend.classes;
    this.discriminator = backend.discriminator;
    this.decls = consoleTypes.declarations;
    this.file = consoleTypes.file;
    this.findings = [];
    this.coveredTs = new Set();
    this.coveredKotlin = new Set();
    this.pairs = new Set();
  }

  tsNameOf(kotlinName) {
    return this.kotlinToTs[kotlinName] ?? kotlinName;
  }

  report(line, message, severity = 'major') {
    this.findings.push({ path: this.file, line, message, severity });
  }

  /** The TS type a ref names, followed through aliases; `seen` stops an alias cycle. */
  resolve(type, seen = new Set()) {
    if (type.kind !== 'ref' || seen.has(type.name) || !this.decls[type.name]) return type;
    seen.add(type.name);
    const { line: _line, ...target } = this.decls[type.name];
    const resolved = this.resolve(target, seen);
    return type.nullable ? { ...resolved, nullable: true } : resolved;
  }

  isStringLike(type) {
    const t = this.resolve(type);
    return t.kind === 'string' || t.kind === 'enum' || (t.kind === 'union' && t.members.every((m) => this.isStringLike(m)));
  }

  /** Compares one Kotlin class with one TS shape, once per pair. */
  compareClass(kotlinName, tsLabel, tsType, line) {
    const kc = this.classes[kotlinName];
    this.coveredKotlin.add(kotlinName);
    const key = `${kotlinName}|${tsLabel}`;
    if (this.pairs.has(key)) return;
    this.pairs.add(key);
    if (kc.kind === 'enum') {
      if (tsType.kind === 'enum') this.compareValues(kc.values, tsType.values, tsLabel, line);
      else if (tsType.kind !== 'string') this.report(line, `${tsLabel} is ${describeTs(tsType)}, but Kotlin ${kotlinName} is an enum`);
    } else if (kc.kind === 'class' && tsType.kind === 'object') {
      this.compareFields(kc.fields, tsType.fields, { kotlin: kotlinName, ts: tsLabel, usage: kc.usage, line });
    } else if (kc.kind === 'sealed' && tsType.kind === 'union') {
      this.compareSealedUnion(kotlinName, kc, tsType, tsLabel, line);
    } else if (kc.kind === 'sealed' && tsType.kind === 'object') {
      this.compareFlattened(kotlinName, kc, tsType, tsLabel, line);
    } else {
      this.report(line, `${tsLabel} is ${describeTs(tsType)}, but Kotlin ${kotlinName} is a ${kc.kind}`);
    }
  }

  compareValues(kotlinValues, tsValues, label, line) {
    const missing = kotlinValues.filter((v) => !tsValues.includes(v));
    const extra = tsValues.filter((v) => !kotlinValues.includes(v));
    if (missing.length) this.report(line, `${label} lacks ${missing.map((v) => `'${v}'`).join(', ')}, which the backend sends`);
    if (extra.length) this.report(line, `${label} has ${extra.map((v) => `'${v}'`).join(', ')}, which the backend never sends`, 'minor');
  }

  compareFields(kotlinFields, tsFields, ctx) {
    const tsByName = new Map(tsFields.map((f) => [f.name, f]));
    const kotlinNames = new Set(kotlinFields.map((f) => f.name));
    const request = ctx.usage.includes('request');
    for (const k of kotlinFields) {
      const t = tsByName.get(k.name);
      if (t) this.compareField(k, t, ctx);
      else if (!this.unmirrored[`${ctx.kotlin}.${k.name}`]) {
        const required = request && !k.optional;
        this.report(
          ctx.line,
          `${ctx.ts} lacks ${k.name}, which Kotlin ${ctx.kotlin} ${required ? 'requires' : 'has'}`,
          required ? 'major' : 'minor',
        );
      }
    }
    for (const t of tsFields) {
      if (!kotlinNames.has(t.name)) this.report(t.line, `${ctx.ts}.${t.name} has no field in Kotlin ${ctx.kotlin}`);
    }
  }

  compareField(k, t, ctx) {
    const label = `${ctx.ts}.${k.name}`;
    const tsNullable = Boolean(this.resolve(t.type).nullable);
    if (ctx.usage.includes('response')) {
      if (k.nullable && !tsNullable) this.report(t.line, `${label} does not allow null, but Kotlin ${ctx.kotlin}.${k.name} is nullable`);
      if (k.partial && !t.optional) this.report(t.line, `${label} is required, but only some Kotlin ${ctx.kotlin} subclasses have it`);
    }
    if (ctx.usage.includes('request')) {
      if (!k.optional && t.optional) this.report(t.line, `${label} is optional, but Kotlin ${ctx.kotlin}.${k.name} is required`);
      if (!k.nullable && tsNullable) this.report(t.line, `${label} allows null, but Kotlin ${ctx.kotlin}.${k.name} does not`);
    }
    this.compareType(k.type, t.type, label, t.line);
  }

  compareType(k, t, label, line) {
    if (t.kind === 'unknown' || k.kind === 'json') return;
    if (t.kind === 'ref' && (k.kind === 'class' || k.kind === 'enum')) return this.compareRef(k, t, label, line);
    const resolved = this.resolve(t);
    if (resolved.kind === 'unknown') return;
    if (k.kind === 'string' && this.isStringLike(resolved)) return;
    if ((k.kind === 'number' || k.kind === 'boolean') && resolved.kind === k.kind) return;
    if ((k.kind === 'list' || k.kind === 'map') && resolved.kind === k.kind) {
      return this.compareType(k.of, resolved.of, `${label}${k.kind === 'list' ? '[]' : '{}'}`, line);
    }
    if (k.kind === 'enum' && resolved.kind === 'string') return void this.coveredKotlin.add(k.class);
    if ((k.kind === 'class' || k.kind === 'enum') && ['object', 'union', 'enum'].includes(resolved.kind)) {
      return this.compareClass(k.class, label, resolved, line);
    }
    this.report(line, `${label} is ${describeTs(t)}, but Kotlin has ${describeKotlin(k)}`);
  }

  /** A field typed by name: the TS mirror of its Kotlin class, compared where it is declared. */
  compareRef(k, t, label, line) {
    const expected = this.tsNameOf(k.class);
    if (t.name === expected) return;
    if (this.decls[expected]) {
      this.report(line, `${label} is ${t.name}, but Kotlin has ${describeKotlin(k)}, mirrored by ${expected}`);
      return;
    }
    const resolved = this.resolve(t);
    if (resolved.kind === 'ref') return; // A name that is not declared in the file: tsc reports it.
    this.coveredTs.add(t.name);
    this.compareClass(k.class, t.name, resolved, this.decls[t.name].line);
  }

  compareSealedUnion(kotlinName, kc, union, tsLabel, line) {
    const variants = new Map();
    for (const member of union.members) {
      const shape = this.resolve(member);
      const tag = shape.kind === 'object' && shape.fields.find((f) => f.name === this.discriminator);
      const value = tag && tag.type.kind === 'enum' && tag.type.values.length === 1 ? tag.type.values[0] : null;
      const memberLine = member.kind === 'ref' ? (this.decls[member.name]?.line ?? line) : (tag?.line ?? shape.fields?.[0]?.line ?? line);
      if (value === null) {
        this.report(memberLine, `${tsLabel} member ${describeTs(member)} has no single '${this.discriminator}' literal`);
        continue;
      }
      if (member.kind === 'ref') this.coveredTs.add(member.name);
      variants.set(value, { shape, label: member.kind === 'ref' ? member.name : `${tsLabel}<${value}>`, line: memberLine });
    }
    for (const sub of kc.subclasses) {
      const variant = variants.get(sub.serialName);
      if (!variant) {
        this.report(line, `${tsLabel} lacks the '${sub.serialName}' variant, which Kotlin ${kotlinName} sends`);
        continue;
      }
      const fields = variant.shape.fields.filter((f) => f.name !== this.discriminator);
      this.compareFields(sub.fields, fields, { kotlin: `${kotlinName}<${sub.serialName}>`, ts: variant.label, usage: kc.usage, line: variant.line });
    }
    const known = new Set(kc.subclasses.map((s) => s.serialName));
    for (const [value, variant] of variants) {
      if (!known.has(value)) this.report(variant.line, `${variant.label} is variant '${value}', which Kotlin ${kotlinName} does not have`);
    }
  }

  /** One TS interface for a whole sealed class: the discriminator plus every subclass's fields. */
  compareFlattened(kotlinName, kc, object, tsLabel, line) {
    const tag = object.fields.find((f) => f.name === this.discriminator);
    const serialNames = kc.subclasses.map((s) => s.serialName);
    if (!tag) this.report(line, `${tsLabel} has no '${this.discriminator}' field for the Kotlin ${kotlinName} subclasses`);
    else if (tag.type.kind === 'enum') this.compareValues(serialNames, tag.type.values, `${tsLabel}.${this.discriminator}`, tag.line);
    const merged = new Map();
    for (const sub of kc.subclasses) {
      for (const field of sub.fields) {
        const entry = merged.get(field.name) ?? { field, count: 0 };
        entry.count += 1;
        merged.set(field.name, entry);
      }
    }
    const fields = [...merged.values()].map(({ field, count }) =>
      count === kc.subclasses.length ? field : { ...field, optional: true, partial: true },
    );
    const tsFields = object.fields.filter((f) => f.name !== this.discriminator);
    this.compareFields(fields, tsFields, { kotlin: kotlinName, ts: tsLabel, usage: kc.usage, line });
  }
}

const TABLES = { renames: RENAMES, consoleOnly: CONSOLE_ONLY, unmirrored: UNMIRRORED_FIELDS };

/** Findings for one backend export and one console extract (both parsed JSON). */
export function compare(backend, consoleTypes, tables = TABLES) {
  const c = new Comparison(backend, consoleTypes, tables);
  for (const [name, reason] of Object.entries(tables.consoleOnly)) {
    if (c.decls[name]) c.coveredTs.add(name);
    else c.report(1, `CONSOLE_ONLY lists ${name} (${reason}), which ${c.file} no longer declares`, 'minor');
  }
  for (const [tsName, kotlinName] of Object.entries(tables.renames)) {
    if (!c.decls[tsName]) c.report(1, `RENAMES maps ${tsName} to Kotlin ${kotlinName}, but ${c.file} no longer declares it`, 'minor');
  }
  for (const [name, decl] of Object.entries(c.decls)) {
    if (c.coveredTs.has(name)) continue;
    const kotlinName = tables.renames[name] ?? name;
    if (!c.classes[kotlinName]) continue;
    c.coveredTs.add(name);
    const { line, ...type } = decl;
    c.compareClass(kotlinName, name, type, line);
  }
  for (const [name, decl] of Object.entries(c.decls)) {
    if (c.coveredTs.has(name)) continue;
    const expected = tables.renames[name] ?? name;
    c.report(decl.line, `${name} has no exported Kotlin DTO ${expected}: add it to DtoContract, RENAMES or CONSOLE_ONLY`);
  }
  for (const name of Object.keys(c.classes)) {
    if (!c.coveredKotlin.has(name)) c.report(1, `Kotlin ${name} is exported, but no TS type mirrors it`, 'minor');
  }
  const findings = c.findings.sort((a, b) => a.line - b.line || a.message.localeCompare(b.message));
  return { findings, compared: c.pairs.size };
}

const readJson = (file, what) => {
  if (!fs.existsSync(file)) throw new Error(`${file} not found: ${what}`);
  return JSON.parse(fs.readFileSync(file, 'utf8'));
};

export function main([
  backendFile = 'app/app/build/reports/dto-contract/backend-dtos.json',
  consoleFile = 'console/reports/dto-contract/console-dtos.json',
  outDir = 'reports/quality/dto-contract',
]) {
  const backend = readJson(backendFile, 'did :app:test run DtoContractTest?');
  const consoleTypes = readJson(consoleFile, 'did npm run quality:report run?');
  const { findings, compared } = compare(backend, consoleTypes);
  const extra = { compared, backendClasses: Object.keys(backend.classes).length, consoleTypes: Object.keys(consoleTypes.declarations).length };
  return writeCheck(outDir, CHECK, 'Console DTO mirrors vs backend DTOs', findings, extra).split('\n').slice(0, 41).join('\n') + '\n';
}

runMain(import.meta, main);
