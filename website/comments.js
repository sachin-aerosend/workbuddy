// Feedback & help threads: name-only posting, replies, pinned maker post, official answers.
(() => {
  const API = '/api/comments';
  const threadsEl = document.getElementById('threads');
  const composer = document.getElementById('composer');
  const nameInput = document.getElementById('c-name');
  const avatarEl = document.getElementById('composer-avatar');
  const openedAt = Date.now();
  let comments = [];

  const store = {
    get: k => { try { return localStorage.getItem(k); } catch { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
  };
  nameInput.value = store.get('wb-name') || '';

  // Every name gets its own cat colour (the icon, hue-shifted).
  const hue = name => { let h = 0; for (const c of name) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };
  const paintAvatar = (img, name, role) => {
    img.style.filter = role === 'maker' || role === 'team' ? '' : `hue-rotate(${hue(name || '?')}deg) saturate(1.15)`;
  };
  nameInput.addEventListener('input', () => paintAvatar(avatarEl, nameInput.value.trim()));
  paintAvatar(avatarEl, nameInput.value.trim());

  function ago(iso) {
    const s = (Date.now() - new Date(iso)) / 1000;
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)} min ago`;
    if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
    if (s < 86400 * 30) return `${Math.floor(s / 86400)} d ago`;
    return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
  }

  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const BADGES = { maker: 'maker', team: 'team' }; // "Common question" posts already say so in the name

  function commentNode(c, isReply) {
    const box = el('article', `comment${isReply ? ' reply' : ''}${c.role ? ` role-${c.role}` : ''}`);
    const img = el('img', 'avatar');
    img.src = 'assets/icon.png'; img.alt = ''; img.width = isReply ? 32 : 40; img.height = img.width;
    paintAvatar(img, c.name, c.role);
    const body = el('div', 'comment-body');
    const meta = el('div', 'meta');
    meta.append(el('b', 'who', c.name));
    if (BADGES[c.role]) meta.append(el('span', `badge badge-${c.role}`, BADGES[c.role]));
    if (!isReply && c.kind === 'help' && c.role !== 'faq') meta.append(el('span', 'badge badge-help', 'needs help'));
    if (c.pinned) meta.append(el('span', 'badge badge-pin', '📌 pinned'));
    meta.append(el('time', 'when', ago(c.at)));
    body.append(meta, el('p', 'text', c.text));
    box.append(img, body);
    return box;
  }

  function render() {
    const byParent = new Map();
    for (const c of comments) if (c.parentId) (byParent.get(c.parentId) || byParent.set(c.parentId, []).get(c.parentId)).push(c);
    const tops = comments.filter(c => !c.parentId);
    // Pinned first, then real conversations by latest activity, with the common questions after new posts.
    const latest = c => Math.max(+new Date(c.at), ...(byParent.get(c.id) || []).map(r => +new Date(r.at)));
    tops.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (a.role === 'faq') - (b.role === 'faq') || latest(b) - latest(a));

    threadsEl.replaceChildren();
    for (const t of tops) {
      const thread = el('div', 'card thread');
      thread.append(commentNode(t, false));
      const replies = (byParent.get(t.id) || []).sort((a, b) => new Date(a.at) - new Date(b.at));
      const list = el('div', 'replies');
      replies.forEach(r => list.append(commentNode(r, true)));
      const replyBtn = el('button', 'reply-btn', replies.length ? `Reply (${replies.length})` : 'Reply');
      replyBtn.type = 'button';
      replyBtn.addEventListener('click', () => openReply(thread, t.id, replyBtn));
      thread.append(list, replyBtn);
      threadsEl.append(thread);
    }
  }

  function openReply(thread, parentId, btn) {
    if (thread.querySelector('.reply-form')) return thread.querySelector('.reply-form textarea').focus();
    const form = el('form', 'reply-form');
    const name = el('input'); name.placeholder = 'Your name'; name.maxLength = 24; name.value = store.get('wb-name') || ''; name.required = true;
    const text = el('textarea'); text.rows = 2; text.maxLength = 800; text.placeholder = 'Write a reply…'; text.required = true;
    const status = el('span', 'status');
    const send = el('button', 'btn btn-small', 'Reply'); send.type = 'submit';
    const row = el('div', 'composer-bottom'); row.append(status, send);
    form.append(name, text, row);
    btn.before(form);
    btn.hidden = true;
    (name.value ? text : name).focus();
    form.addEventListener('submit', e => { e.preventDefault(); submit({ name: name.value, text: text.value, parentId }, status, send); });
  }

  async function submit(payload, status, button) {
    status.textContent = 'Posting…'; status.className = 'status'; button.disabled = true;
    try {
      const res = await fetch(API, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...payload, website: composer.website.value, elapsed: Date.now() - openedAt }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || 'Something went wrong. Try again?');
      store.set('wb-name', payload.name.trim());
      comments.push(data.comment);
      render();
      return true;
    } catch (err) {
      status.textContent = err.message; status.className = 'status error'; button.disabled = false;
      return false;
    }
  }

  composer.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = composer.querySelector('button[type=submit]');
    const ok = await submit({ name: nameInput.value, text: composer.text.value, kind: composer.kind.value }, document.getElementById('c-status'), btn);
    if (ok) {
      composer.text.value = '';
      btn.disabled = false;
      const s = document.getElementById('c-status'); s.textContent = 'Posted 🐾'; s.className = 'status ok';
    }
  });

  async function load() {
    try {
      const res = await fetch(API, { cache: 'no-store' });
      if (!res.ok) throw new Error();
      comments = (await res.json()).comments || [];
      render();
    } catch {
      threadsEl.replaceChildren(el('p', 'threads-empty', 'Comments are taking a nap. Try refreshing in a moment.'));
    }
  }
  // Only fetch when someone scrolls near the section.
  new IntersectionObserver((entries, obs) => { if (entries[0].isIntersecting) { obs.disconnect(); load(); } }, { rootMargin: '600px' }).observe(threadsEl);
})();
