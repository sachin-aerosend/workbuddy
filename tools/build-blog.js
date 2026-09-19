// Builds the static blog: website/blog-src/*.md -> website/blog/<slug>/index.html,
// plus the blog index, sitemap.xml and robots.txt.   usage: node tools/build-blog.js
const fs = require('fs');
const path = require('path');

const SITE = 'https://workbuddycat.vercel.app';
const DOWNLOAD = 'https://github.com/sachin-aerosend/workbuddy/releases/latest/download/WorkBuddy-Setup.exe';
const WEB = path.join(__dirname, '..', 'website');
const SRC = path.join(WEB, 'blog-src');
const OUT = path.join(WEB, 'blog');

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const slugify = s => s.toLowerCase().replace(/<[^>]+>/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

// ---------- tiny markdown subset ----------
function inline(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, t, u) => {
      const ext = /^https?:/.test(u) && !u.startsWith(SITE);
      return `<a href="${u}"${ext ? ' rel="noopener" target="_blank"' : ''}>${t}</a>`;
    });
}
function markdown(md) {
  const out = [], toc = [];
  // A heading line directly followed by text (no blank line) becomes its own block.
  const blocks = md.replace(/\r/g, '').split(/\n{2,}/).flatMap(b => {
    const m = b.match(/^(#{2,3} [^\n]+)\n([\s\S]+)$/);
    return m ? [m[1], m[2]] : [b];
  });
  for (const raw of blocks) {
    const b = raw.trim();
    if (!b) continue;
    if (b === ':::cta') { out.push(ctaBox()); continue; }
    let m;
    if ((m = b.match(/^(#{2,3}) (.+)$/))) {
      const id = slugify(m[2]);
      if (m[1] === '##') toc.push({ id, text: m[2] });
      out.push(`<h${m[1].length} id="${id}">${inline(m[2])}</h${m[1].length}>`);
    } else if (/^- /.test(b)) {
      out.push(`<ul>${b.split(/\n(?=- )/).map(li => `<li>${inline(li.replace(/^- /, '').replace(/\n\s*/g, ' '))}</li>`).join('')}</ul>`);
    } else if (/^\d+\. /.test(b)) {
      out.push(`<ol>${b.split(/\n(?=\d+\. )/).map(li => `<li>${inline(li.replace(/^\d+\. /, '').replace(/\n\s*/g, ' '))}</li>`).join('')}</ol>`);
    } else if (/^> /.test(b)) {
      out.push(`<aside class="tip"><span class="tip-cat" aria-hidden="true"></span><p>${inline(b.replace(/^> ?/gm, '').replace(/\n/g, ' '))}</p></aside>`);
    } else {
      out.push(`<p>${inline(b.replace(/\n/g, ' '))}</p>`);
    }
  }
  return { html: out.join('\n'), toc };
}

function ctaBox() {
  return `<div class="cta-box">
  <div class="sprite" data-anim="swipe"></div>
  <div>
    <h3>Let a tiny cat handle it</h3>
    <p>WorkBuddy is a free pixel cat for your Windows taskbar. It closes Reels and Shorts tabs, nudges you about forgotten tabs, and keeps you company while you work.</p>
    <a class="btn btn-small" href="${DOWNLOAD}">Download for Windows</a> <a class="text-link" href="/">See how it works →</a>
  </div>
</div>`;
}

// ---------- page shell ----------
const FONTS = '<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin><link href="https://fonts.googleapis.com/css2?family=Pixelify+Sans:wght@500;600;700&family=Nunito:wght@500;600;700;800&display=swap" rel="stylesheet">';
function shell({ title, description, canonical, type = 'website', jsonld, body }) {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<link rel="canonical" href="${canonical}">
<meta property="og:type" content="${type}">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:url" content="${canonical}">
<meta property="og:image" content="${SITE}/assets/og.png">
<meta property="og:site_name" content="WorkBuddy">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#fffaf2">
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="alternate" type="application/rss+xml" title="WorkBuddy blog" href="${SITE}/blog/feed.xml">
${FONTS}
<link rel="stylesheet" href="/style.css">
<link rel="stylesheet" href="/blog.css">
${jsonld ? `<script type="application/ld+json">${JSON.stringify(jsonld)}</script>` : ''}
</head>
<body>
<header class="nav">
  <a class="brand" href="/" aria-label="WorkBuddy home"><img src="/assets/icon.png" alt="" width="36" height="36"><span>WorkBuddy</span></a>
  <nav class="nav-links" aria-label="Main">
    <a href="/#features">What it does</a>
    <a href="/blog/">Blog</a>
    <a href="/#community">Community</a>
  </nav>
  <a class="btn btn-small" href="${DOWNLOAD}">Download</a>
</header>
<main class="blog-main">
${body}
</main>
<footer class="foot">
  <div class="foot-inner">
    <a class="brand" href="/"><img src="/assets/icon.png" alt="" width="28" height="28"><span>WorkBuddy</span></a>
    <p><a href="/blog/">Blog</a> · <a href="/#faq">FAQ</a> · <a href="https://github.com/sachin-aerosend/workbuddy">GitHub</a> · Made with 🧡 and a lot of tiny pixels.</p>
  </div>
</footer>
<script src="/blog.js"></script>
</body>
</html>
`;
}

const fmtDate = d => new Date(d + 'T00:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
const readMins = text => Math.max(2, Math.round(text.split(/\s+/).length / 220));

// ---------- load posts ----------
function parse(file) {
  const raw = fs.readFileSync(file, 'utf8').replace(/^﻿/, '');
  const m = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!m) throw new Error(`missing front matter: ${file}`);
  const meta = Object.fromEntries(m[1].split('\n').filter(Boolean).map(l => {
    const i = l.indexOf(':');
    return [l.slice(0, i).trim(), l.slice(i + 1).trim().replace(/^"(.*)"$/, '$1')];
  }));
  return { ...meta, body: m[2], slug: meta.slug || path.basename(file, '.md') };
}
const posts = fs.readdirSync(SRC).filter(f => f.endsWith('.md')).map(f => parse(path.join(SRC, f)))
  .sort((a, b) => (b.date || '').localeCompare(a.date || '') || (+a.order || 99) - (+b.order || 99));

fs.rmSync(OUT, { recursive: true, force: true });
for (const p of posts) {
  const { html, toc } = markdown(p.body);
  const url = `${SITE}/blog/${p.slug}/`;
  const i = posts.indexOf(p);
  const related = [1, 2, 3].map(k => posts[(i + k) % posts.length]); // next three, wrapping around
  const body = `
<article class="post">
  <header class="post-head">
    <a class="back" href="/blog/">← All posts</a>
    <p class="eyebrow">${esc(p.tag || 'Focus')}</p>
    <h1>${esc(p.title)}</h1>
    <p class="post-meta">By Sachin Sahani · <time datetime="${p.date}">${fmtDate(p.date)}</time> · ${readMins(p.body)} min read</p>
    <div class="post-hero"><div class="sprite big" data-anim="${esc(p.anim || 'idle')}"></div></div>
    <p class="post-lede">${inline(p.description)}</p>
  </header>
  ${toc.length > 2 ? `<nav class="toc card" aria-label="In this post"><b>In this post</b><ol>${toc.map(t => `<li><a href="#${t.id}">${inline(t.text)}</a></li>`).join('')}</ol></nav>` : ''}
  <div class="post-body">
${html}
  </div>
</article>
<section class="related">
  <h2>Keep reading</h2>
  <div class="post-grid">${related.map(card).join('')}</div>
</section>`;
  const jsonld = {
    '@context': 'https://schema.org', '@type': 'BlogPosting',
    headline: p.title, description: p.description, datePublished: p.date, dateModified: p.updated || p.date,
    author: { '@type': 'Person', name: 'Sachin Sahani' },
    publisher: { '@type': 'Organization', name: 'WorkBuddy', logo: { '@type': 'ImageObject', url: `${SITE}/assets/icon.png` } },
    image: `${SITE}/assets/og.png`, mainEntityOfPage: url, keywords: p.keywords,
  };
  fs.mkdirSync(path.join(OUT, p.slug), { recursive: true });
  fs.writeFileSync(path.join(OUT, p.slug, 'index.html'), shell({ title: `${p.title} | WorkBuddy`, description: p.description, canonical: url, type: 'article', jsonld, body }));
}

function card(p) {
  return `<a class="post-card card" href="/blog/${p.slug}/">
  <div class="post-card-art"><div class="sprite" data-anim="${esc(p.anim || 'idle')}"></div></div>
  <span class="eyebrow">${esc(p.tag || 'Focus')}</span>
  <h3>${esc(p.title)}</h3>
  <p>${esc(p.description)}</p>
  <span class="post-card-meta">${readMins(p.body)} min read</span>
</a>`;
}

// blog index
fs.writeFileSync(path.join(OUT, 'index.html'), shell({
  title: 'WorkBuddy Blog: focus tips, fewer tabs, less doomscrolling',
  description: 'Practical, friendly guides to beating doomscrolling, taming browser tabs, and staying focused at your desk, from the maker of WorkBuddy.',
  canonical: `${SITE}/blog/`,
  jsonld: { '@context': 'https://schema.org', '@type': 'Blog', name: 'WorkBuddy Blog', url: `${SITE}/blog/` },
  body: `
<section class="blog-hero">
  <p class="eyebrow">The WorkBuddy blog</p>
  <h1>Fewer Reels. Fewer tabs. More done.</h1>
  <p class="sub">Practical guides to staying focused at your desk, written by the person who built a cat to keep him off Instagram.</p>
</section>
<section class="post-grid">${posts.map(card).join('')}</section>`,
}));

// RSS feed
fs.writeFileSync(path.join(OUT, 'feed.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>WorkBuddy Blog</title><link>${SITE}/blog/</link><description>Focus tips from the maker of WorkBuddy</description>
${posts.map(p => `<item><title>${esc(p.title)}</title><link>${SITE}/blog/${p.slug}/</link><guid>${SITE}/blog/${p.slug}/</guid><pubDate>${new Date(p.date + 'T09:00:00Z').toUTCString()}</pubDate><description>${esc(p.description)}</description></item>`).join('\n')}
</channel></rss>
`);

// sitemap + robots
const today = new Date().toISOString().slice(0, 10);
const urls = [{ loc: `${SITE}/`, lastmod: today, pri: '1.0' }, { loc: `${SITE}/blog/`, lastmod: today, pri: '0.8' },
  ...posts.map(p => ({ loc: `${SITE}/blog/${p.slug}/`, lastmod: p.updated || p.date, pri: '0.7' }))];
fs.writeFileSync(path.join(WEB, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls.map(u => `  <url><loc>${u.loc}</loc><lastmod>${u.lastmod}</lastmod><priority>${u.pri}</priority></url>`).join('\n')}
</urlset>
`);
fs.writeFileSync(path.join(WEB, 'robots.txt'), `User-agent: *\nAllow: /\nDisallow: /api/\n\nSitemap: ${SITE}/sitemap.xml\n`);
console.log(`built ${posts.length} posts -> website/blog/, sitemap.xml (${urls.length} urls), robots.txt, feed.xml`);
