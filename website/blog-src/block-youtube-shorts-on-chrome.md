---
title: How to Block YouTube Shorts on Chrome (and Keep Regular YouTube)
description: You need YouTube for tutorials and talks, not an endless Shorts feed. Here are the ways to hide or block Shorts on desktop Chrome, and the catch most guides miss.
date: 2026-09-19
order: 2
tag: Browser tips
anim: pounce
keywords: block youtube shorts, hide youtube shorts chrome, disable shorts desktop, remove shorts from youtube
---
YouTube is genuinely useful for work: product demos, conference talks, "how do I do X in Excel". Shorts is the part that turns a five-minute tutorial into an hour of vertical videos.

There's no official "turn Shorts off" switch on desktop YouTube, but you have good options. Here they are, from lightest to strongest.

## Option 1: Tell YouTube you're not interested

On the YouTube homepage, the Shorts shelf has a menu (the ⋮ or ✕ on the shelf). Choosing to show fewer Shorts hides the shelf for a while.

- **Good:** built in, nothing to install.
- **Not so good:** it tends to come back after some time, and it doesn't stop you opening a Short from search or a link.

## Option 2: Hide Shorts with a content-blocking extension

Ad-blocking extensions like **uBlock Origin** let you add your own "cosmetic filters" that hide parts of a page. The community shares filters that hide the Shorts shelf and the Shorts button in the sidebar. They usually target YouTube's page elements, for example:

- `youtube.com##ytd-reel-shelf-renderer`
- `youtube.com##ytd-rich-shelf-renderer[is-shorts]`

You add them under the extension's **My filters** tab.

- **Good:** very effective at removing Shorts from what you see.
- **Not so good:** YouTube changes its page structure from time to time, so filters sometimes need updating.

> Hiding the Shorts *shelf* removes the temptation. Blocking the Shorts *pages* removes the escape hatch. For best results, do both.

## Option 3: Block the Shorts pages themselves

Every Short lives at an address starting with `youtube.com/shorts/`. Blocking that path stops Shorts from playing even if you click a link to one, while normal videos (`youtube.com/watch?v=…`) keep working.

**The catch most guides miss:** YouTube is a single-page app. When you click a Short from inside YouTube, the page doesn't fully reload; the address just changes. Many URL blockers only check when a page *loads*, so they catch a Shorts link opened in a new tab but miss the one you clicked on the homepage. If you pick a blocker, check that it reacts to in-page navigation too.

## Option 4: Make YouTube a "search only" site

A behavioural trick that works surprisingly well:

- Bookmark `youtube.com/results` or use your browser's address bar to search YouTube directly.
- Avoid the homepage, where recommendations and Shorts live.
- Turn off watch history if you don't want a personalised feed at all (**You → History → Pause watch history**). With history paused, the homepage becomes much less sticky.

## What about the mobile app?

This guide is about desktop Chrome. On phones, your options are your platform's app limits (**Digital Wellbeing** on Android, **Screen Time** on iPhone) or using YouTube in the mobile browser, which you can block paths in more easily.

:::cta

## Which should you choose?

- **Just want the shelf gone?** Option 2.
- **Keep clicking Shorts from links or search?** Option 3, with a blocker that catches in-page navigation.
- **Want the habit gone for good?** Option 4 plus any of the above.

I built WorkBuddy partly because of that single-page-app catch: its browser extension watches for address changes, so the cat catches a Short however you got there, then closes the tab and leaves the rest of YouTube alone.
