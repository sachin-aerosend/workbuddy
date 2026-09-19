# WorkBuddy 🐾

A tiny pixel cat that lives on your Windows taskbar. It naps in the corners, types along with you,
and swats away Reels and Shorts tabs before you get sucked in.

**Website:** https://www.workbuddycat.online · **Download:** [latest release](https://github.com/sachin-aerosend/workbuddy/releases/latest)

![WorkBuddy](website/assets/og.png)

## What it does

- **Closes time-wasting tabs:** Instagram Reels, YouTube Shorts and Facebook Reels by default. The page blurs, the cat runs over and swipes the tab shut.
- **Nudges forgotten tabs:** a thought bubble asks whether to close tabs you haven't touched in an hour (with undo).
- **Types along with you:** a tiny keyboard, left/right paws follow your keys. It only knows *which side* you pressed, never *what*.
- **Lives on your screens:** walks the taskbar, strolls on your tab bar, hops between monitors, plays with yarn, naps when you're away. Drag it and drop it and it lands.
- **Invisible on screen share** and hidden during full-screen apps.

## Project layout

| Folder | What's in it |
|---|---|
| `app/` | Electron desktop app (main process, cat "brain", renderer, local bridge) |
| `extension/` | Chrome / Edge / Brave extension (MV3) that watches tabs and talks to the app on `127.0.0.1:47821` |
| `website/` | Static landing page (deployed on Vercel) |
| `android/` | WorkBuddy for Android (Kotlin): overlay cat, accessibility watcher for Reels/Shorts, reminders |
| `tools/` | Sprite generator, icon builder, tests and dev helpers |

## Develop

```bash
npm install
npm start          # run the app
npm test           # headless behaviour simulation
npm run dist       # build the Windows installer into dist/
```

Load the extension with **chrome://extensions → Developer mode → Load unpacked → `extension/`**.

`npm run sprites` regenerates the art from the original CatPack source files, which are not in this repo
(they're a third-party asset pack). The generated sprites in `app/renderer/sprites/` are included.
