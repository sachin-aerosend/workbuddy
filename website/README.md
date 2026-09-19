# WorkBuddy website

A static site (plain HTML/CSS/JS, no build step). Open `index.html` to preview it.

## Put it online (free)

1. **Installer download:** create a GitHub repo, then a Release, and attach `dist/WorkBuddy Setup 1.0.0.exe`.
   Paste that release asset URL into `DOWNLOAD_URL` at the top of `main.js`.
2. **Site:** in Cloudflare, go to Workers & Pages → Create → Pages → *Upload assets* and drag in this `website` folder.
   You get a free `something.pages.dev` address right away.
3. **Domain (optional):** in the Pages project, open Custom domains and add your domain (e.g. `workbuddycat.com`).

## Updating the art

The sprites in `assets/sprites/` are copies of `app/renderer/sprites/`. After `npm run sprites`, copy them over again.
`assets/og.png` (the link-preview image) is a screenshot of `index.html?pose=hunt`.
