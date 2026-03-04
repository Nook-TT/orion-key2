const ALLOWED_INLINE_TAGS = new Set([
  "a",
  "b",
  "br",
  "code",
  "em",
  "i",
  "mark",
  "small",
  "span",
  "strong",
  "sub",
  "sup",
  "u",
])

const BLOCKED_TAGS = ["script", "style", "iframe", "object", "embed", "meta", "link"]
const SAFE_URL_RE = /^(https?:|mailto:|tel:|\/|#)/i

function escapeHtmlAttr(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("\"", "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
}

export function sanitizeConfigHtml(input?: string | null): string {
  if (!input) {
    return ""
  }

  let html = String(input)
    .replace(/<!--[\s\S]*?-->/g, "")

  for (const tag of BLOCKED_TAGS) {
    const pairedTagRe = new RegExp(`<\\s*${tag}\\b[^>]*>[\\s\\S]*?<\\s*\\/\\s*${tag}\\s*>`, "gi")
    const selfClosingTagRe = new RegExp(`<\\s*${tag}\\b[^>]*\\/?>`, "gi")
    html = html.replace(pairedTagRe, "")
    html = html.replace(selfClosingTagRe, "")
  }

  return html.replace(/<\s*(\/?)\s*([a-zA-Z0-9-]+)([^>]*)>/g, (full, slash, rawTagName, rawAttrs) => {
    const tagName = String(rawTagName).toLowerCase()
    if (!ALLOWED_INLINE_TAGS.has(tagName)) {
      return ""
    }

    const isClosing = slash === "/"
    if (isClosing) {
      return tagName === "br" ? "" : `</${tagName}>`
    }

    if (tagName === "br") {
      return "<br />"
    }

    if (tagName === "a") {
      const hrefMatch = /href\s*=\s*(['"])(.*?)\1/i.exec(rawAttrs) ?? /href\s*=\s*([^\s>]+)/i.exec(rawAttrs)
      const targetMatch = /target\s*=\s*(['"])(.*?)\1/i.exec(rawAttrs) ?? /target\s*=\s*([^\s>]+)/i.exec(rawAttrs)

      const rawHref = hrefMatch?.[2] ?? hrefMatch?.[1] ?? ""
      const href = SAFE_URL_RE.test(rawHref) ? rawHref : "#"

      const rawTarget = (targetMatch?.[2] ?? targetMatch?.[1] ?? "").toLowerCase()
      const target = rawTarget === "_blank" ? "_blank" : rawTarget === "_self" ? "_self" : ""

      let attrs = ` href="${escapeHtmlAttr(href)}"`
      if (target) {
        attrs += ` target="${target}"`
      }
      if (target === "_blank") {
        attrs += ` rel="noopener noreferrer nofollow"`
      }
      return `<a${attrs}>`
    }

    return `<${tagName}>`
  })
}

export function stripHtmlTags(input?: string | null): string {
  if (!input) {
    return ""
  }

  return String(input)
    .replace(/<br\s*\/?>/gi, " ")
    .replace(/<[^>]*>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/\s+/g, " ")
    .trim()
}
