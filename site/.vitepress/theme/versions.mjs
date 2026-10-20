// The version menu's logic, kept out of the component so it can be tested. versions.json sits one
// level above every version's base: /homer/versions.json for /homer/1.34.0/ (assemble.mjs).

/** The URL of the directory holding versions.json, for a site built under [base]. */
export function versionsRoot(base, origin) {
  return new URL('../', new URL(base, origin))
}

/** Menu entries for the version names in [list], linking each to its directory under [root]. */
export function menuEntries(list, root) {
  if (!Array.isArray(list)) return []
  return list
    .filter((name) => typeof name === 'string' && /^(\d+\.\d+\.\d+|next)$/.test(name))
    .map((name) => ({ name, path: new URL(`${name}/`, root).pathname }))
}
