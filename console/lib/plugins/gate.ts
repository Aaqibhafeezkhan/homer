import type { Capabilities } from '@/lib/auth/use-can';
import type { PluginRequirement } from './types';

/**
 * The single plugin access gate. Both the pre-fetch filter in
 * `PluginsProvider` and the handler check in the plugin route call this, and
 * nothing else may reimplement it: the guarantee that a denial is
 * indistinguishable from a nonexistent plugin (root CLAUDE.md §12 rule 3) only
 * holds while every call site agrees on the answer.
 */
export function canUsePlugin(
  requires: PluginRequirement | undefined,
  { isAdmin, can }: Pick<Capabilities, 'isAdmin' | 'can'>,
): boolean {
  if (requires === undefined) return true;
  if (requires === 'admin') return isAdmin;
  return can(requires);
}
