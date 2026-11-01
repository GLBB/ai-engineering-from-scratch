(function (root) {
  function accept(sources, seen) {
    const accepted = [];
    for (const source of sources) {
      if (!source || typeof source.label !== 'string' || !source.label.trim()) continue;
      let url;
      try { url = new URL(source.url); } catch (_) { continue; }
      if (url.protocol !== 'https:' || url.username || url.password || seen.has(url.href)) continue;
      seen.add(url.href);
      accepted.push({ label: source.label, url: url.href });
    }
    return accepted;
  }
  const api = { accept };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.sourceLinks = api;
})(globalThis);
