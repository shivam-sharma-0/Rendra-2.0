/**
 * Ref callback for a short inline text element (a label next to icons): fixes its width to the
 * text's width rounded up to a whole pixel. Text is usually a fractional number of pixels wide,
 * which pushes every icon after it in the row off the pixel grid and makes their edges look soft.
 * Measured again once web fonts have loaded. Remount the element (a `key`) when its text changes.
 */
export function snapTextWidth(el: HTMLElement | null) {
  if (!el) return
  const apply = () => {
    el.style.width = ''
    const range = document.createRange()
    range.selectNodeContents(el)
    const width = range.getBoundingClientRect().width
    if (width > 0) el.style.width = `${Math.ceil(width)}px`
  }
  apply()
  void document.fonts?.ready.then(apply)
}
