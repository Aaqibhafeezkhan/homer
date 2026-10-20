// Gives a table's prose columns a minimum width, so a column of sentences is not squeezed to a
// few words per line beside columns of long code names; a table that then outgrows the page
// scrolls sideways instead (the theme's tables already do).

/** Average characters per cell from which a column counts as prose. */
const PROSE = 80
/** Minimum width of a prose column in rem: one rem per this many average characters, clamped. */
const CHARS_PER_REM = 22
const MIN_REM = 10
const MAX_REM = 20

/** markdown-it plugin: sets `min-width` on the header cell of every prose column. */
export function tableWidths(md) {
  md.core.ruler.push('table_widths', (state) => {
    const tokens = state.tokens
    for (let i = 0; i < tokens.length; i++) {
      if (tokens[i].type !== 'table_open') continue
      const heads = []
      const sums = []
      let rows = 0
      let column = -1
      let inBody = false
      for (i++; i < tokens.length && tokens[i].type !== 'table_close'; i++) {
        const token = tokens[i]
        if (token.type === 'tbody_open') inBody = true
        else if (token.type === 'tr_open') {
          column = -1
          if (inBody) rows++
        } else if (token.type === 'th_open' || token.type === 'td_open') {
          column++
          if (token.type === 'th_open') heads[column] = token
        } else if (token.type === 'inline' && inBody) {
          sums[column] = (sums[column] ?? 0) + visibleLength(token)
        }
      }
      if (rows === 0) continue
      heads.forEach((head, c) => {
        const average = (sums[c] ?? 0) / rows
        if (average < PROSE) return
        const rem = Math.min(MAX_REM, Math.max(MIN_REM, Math.round(average / CHARS_PER_REM)))
        const style = head.attrGet('style')
        head.attrSet('style', `${style ? `${style};` : ''}min-width:${rem}rem`)
      })
    }
  })
}

// The characters a reader sees: text and code, not link targets or markup.
function visibleLength(inline) {
  return (inline.children ?? []).reduce(
    (sum, child) => sum + (child.type === 'text' || child.type === 'code_inline' ? child.content.length : 0),
    0,
  )
}
