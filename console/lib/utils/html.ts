// Characters that must be emitted as \uXXXX escapes when embedding JSON inside
// an inline <script>. `<` / `>` stop a `</script>` (or `<!--`) from terminating
// the tag; U+2028 / U+2029 are valid in JSON strings but are line terminators
// in JS, so an unescaped one would break the parse.
const LINE_SEP = String.fromCharCode(0x2028);
const PARA_SEP = String.fromCharCode(0x2029);
const SCRIPT_ESCAPES: Record<string, string> = {
  '<': '\\u003c',
  '>': '\\u003e',
  [LINE_SEP]: '\\u2028',
  [PARA_SEP]: '\\u2029',
};
const SCRIPT_UNSAFE = new RegExp('[<>' + LINE_SEP + PARA_SEP + ']', 'g');

/**
 * Serialize a value to JSON safe for interpolation into an inline `<script>`
 * body. Defense-in-depth: even operator-controlled input can then never break
 * out of the tag or the JS parse.
 */
export function serializeForScript(value: unknown): string {
  return JSON.stringify(value).replace(SCRIPT_UNSAFE, (c) => SCRIPT_ESCAPES[c]);
}
