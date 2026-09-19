// Reminders: small persistent list (userData/reminders.json) so they survive restarts.
const fs = require('fs');
const path = require('path');
const { app } = require('electron');

const file = () => path.join(app.getPath('userData'), 'reminders.json');
let list = [];
try { list = JSON.parse(fs.readFileSync(file(), 'utf8')).filter(r => r && r.id && r.due); } catch { list = []; }
const save = () => { try { fs.writeFileSync(file(), JSON.stringify(list, null, 2)); } catch {} };

module.exports = {
  all: () => [...list].sort((a, b) => a.due - b.due),
  next: () => list.reduce((m, r) => (!m || r.due < m.due ? r : m), null),
  add(text, minutes) {
    const r = { id: `r${Date.now().toString(36)}`, text: String(text || '').trim().slice(0, 120) || 'Time’s up!', due: Date.now() + minutes * 60e3, minutes };
    list.push(r); save();
    return r;
  },
  remove(id) { list = list.filter(r => r.id !== id); save(); },
  // Reminders whose time has come (removed from the list; snoozing re-adds).
  takeDue(now = Date.now()) {
    const due = list.filter(r => r.due <= now);
    if (due.length) { list = list.filter(r => r.due > now); save(); }
    return due;
  },
};
