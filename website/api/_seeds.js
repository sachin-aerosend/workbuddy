// Built-in posts: the maker's welcome and common support questions with official answers.
// They're clearly labelled (maker / team / "Common question"), not presented as posts by real users.
const AT = '2026-09-19T11:30:00.000Z';

export const SEEDS = [
  {
    id: 'welcome', role: 'maker', name: 'Sachin', kind: 'feedback', pinned: true, at: AT,
    text: "Hey! I'm Sachin, and I made WorkBuddy because I kept losing afternoons to Reels 🙈\n\nIf the cat does something weird, or you've got an idea for a new trick (or a whole new cat), drop it here. I read everything.",
  },

  { id: 'faq-not-closing', role: 'faq', name: 'Common question', kind: 'help', at: AT,
    text: "I installed the app but the cat doesn't close my Reels tab. What am I missing?" },
  { id: 'faq-not-closing-a', role: 'team', name: 'WorkBuddy team', parentId: 'faq-not-closing', at: AT,
    text: "It's almost always the browser extension:\n1. Open chrome://extensions and check WorkBuddy Cat is switched on. Its popup should say “Connected to your cat”.\n2. Make sure the cat app is running (cat icon near the clock).\n3. Use more than one Chrome profile? Add the extension in each one.\n4. Just updated? Click the reload ↻ icon on the extension card." },

  { id: 'faq-more-sites', role: 'faq', name: 'Common question', kind: 'help', at: AT,
    text: 'Can I block other sites too, like the LinkedIn feed or X?' },
  { id: 'faq-more-sites-a', role: 'team', name: 'WorkBuddy team', parentId: 'faq-more-sites', at: AT,
    text: 'Yes! Right-click the cat’s tray icon → Edit settings. Add a line to "blockRules", for example:\n{ "name": "X", "match": "x.com/home", "on": true }\nSave the file and it applies within a couple of seconds. "match" is the start of the web address, so instagram.com/reels blocks Reels but leaves the rest of Instagram alone.' },

  { id: 'faq-in-the-way', role: 'faq', name: 'Common question', kind: 'help', at: AT,
    text: 'The cat strolled across my tabs during a call. Can people see it, and can I make it stay put?' },
  { id: 'faq-in-the-way-a', role: 'team', name: 'WorkBuddy team', parentId: 'faq-in-the-way', at: AT,
    text: "Nobody else sees it. It's hidden from screen shares and recordings. If it's in your way, drag it somewhere else, or right-click the tray icon → “Send cat away for 1 hour”. It also steps aside on its own when an app goes full-screen." },

  { id: 'faq-smartscreen', role: 'faq', name: 'Common question', kind: 'help', at: AT,
    text: 'Windows says “Windows protected your PC” when I run the installer. Is it safe?' },
  { id: 'faq-smartscreen-a', role: 'team', name: 'WorkBuddy team', parentId: 'faq-smartscreen', at: AT,
    text: "That warning appears because the installer isn't code-signed yet (a signing certificate is on the to-do list). Click More info → Run anyway. The full source code is on GitHub if you'd like to check exactly what it does." },
];
