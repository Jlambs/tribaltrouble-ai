# Tribal Trouble Wiki: Source Articles

This folder holds the baseline articles for a Tribal Trouble community wiki. They are plain
GitHub-flavored Markdown with no engine-specific syntax, so they can be loaded into GitHub Wiki,
Wiki.js, MkDocs, Docusaurus, BookStack, Outline, MediaWiki/Fandom or any other host with little
or no conversion.

The articles are a starting point. Every page is short enough to read in a few minutes and is
meant to grow as the community adds strategy, history, screenshots and trivia.

## Page list

| Area | Pages |
|---|---|
| Start here | [Home](Home.md), [Getting Started](Getting-Started.md), [FAQ](FAQ.md), [Glossary](Glossary.md) |
| The game | [History](History.md), [Tribes](Tribes.md), [Vikings](Vikings.md), [Natives](Natives.md) |
| Economy | [Resources](Resources.md), [Economy](Economy.md) |
| Units | [Units](Units.md), [Peon](Peon.md), [Rock Warrior](Rock-Warrior.md), [Iron Warrior](Iron-Warrior.md), [Chicken Warrior](Chicken-Warrior.md), [Chieftain](Chieftain.md) |
| Buildings | [Buildings](Buildings.md), [Quarters](Quarters.md), [Armory](Armory.md), [Tower](Tower.md), [Ship](Ship.md) |
| Combat | [Combat Mechanics](Combat-Mechanics.md), [Magic](Magic.md), [Strategy Guide](Strategy-Guide.md) |
| Single player | [Tutorial](Tutorial.md), [Campaigns](Campaigns.md), [Viking Campaign](Viking-Campaign.md), [Native Campaign](Native-Campaign.md) |
| Multiplayer | [Game Setup](Game-Setup.md), [Multiplayer](Multiplayer.md), [Spectating](Spectating.md) |
| Reference | [Controls](Controls.md), [Settings](Settings.md), [Community](Community.md) |
| Wiki upkeep | [Style Guide](Style-Guide.md), page templates in [`templates/`](templates/) |

`_Sidebar.md` is the navigation menu. GitHub Wiki shows it automatically; on other engines,
copy its links into the engine's own navigation setting.

## Conventions

These conventions keep the pages portable. The [Style Guide](Style-Guide.md) has the full
version for editors.

- **One page per file, flat folder.** The file name is the page title with spaces replaced by
  hyphens: `Rock-Warrior.md` is the page "Rock Warrior". This is the naming GitHub Wiki uses,
  and it maps cleanly onto every other engine.
- **Links are relative Markdown links with the `.md` extension**, for example
  `[Peon](Peon.md)` or `[Stun](Magic.md#terrifying-toot)`. They work when browsing the repository on
  GitHub and in MkDocs and Docusaurus. The porting notes below show how to strip the extension
  for engines that want bare page names.
- **No front matter, no HTML, no engine macros.** Categories are a plain line at the bottom of
  each page (`Categories: Units · Vikings`). Infoboxes are ordinary two-column tables placed
  directly under the page title.
- **Numbers come from the game source.** Stat tables were taken from the open-source code in
  this repository. Each mechanics page ends with a "Data sources" note that names the source
  files, so editors can re-check numbers after a balance change.
- **Resurrected-only features are labelled.** Anything that did not exist in the original 2004
  release (ships, the Archipelago map, spectating, the OpenSkill ladder, the Discord bot) is
  marked *Resurrected* the first time it comes up on a page.

## Porting to a wiki host

Run these from inside the `wiki/` folder.

### GitHub Wiki

GitHub Wiki is a separate git repository (`<repo>.wiki.git`). It wants bare page names in
links, so strip the `.md` extension, then copy the pages in:

```bash
git clone https://github.com/<owner>/<repo>.wiki.git ../wiki-repo
```

```bash
cp *.md ../wiki-repo/ && cd ../wiki-repo && rm README.md && sed -i -E 's/\]\(([A-Za-z0-9-]+)\.md(#[^)]*)?\)/](\1\2)/g' *.md
```

`Home.md`, `_Sidebar.md` and `_Footer.md` are picked up automatically by GitHub Wiki.

### MkDocs or Docusaurus

Drop the folder in as the docs directory. Relative `.md` links already resolve. Rename
`Home.md` to `index.md` (MkDocs) or set it as the docs root page (Docusaurus), and build the
nav from `_Sidebar.md`.

### Wiki.js, Outline, BookStack

Use the engine's Markdown import, or paste each page into a new Markdown page with the same
title. Strip the `.md` extension from links first with the `sed` command from the GitHub Wiki
step if the engine uses bare paths.

### MediaWiki or Fandom

Convert each page with pandoc, which turns Markdown tables, headings and links into wikitext:

```bash
mkdir -p mediawiki && for f in *.md; do sed -E 's/\]\(([A-Za-z0-9-]+)\.md(#[^)]*)?\)/](\1\2)/g' "$f" | pandoc -f gfm -t mediawiki -o "mediawiki/${f%.md}.wiki"; done
```

Then, optionally:

- Replace the infobox tables with an `{{Infobox unit}}` / `{{Infobox building}}` template. The
  table rows use the same field names on every page of a type, so this is a find-and-replace
  job. Field lists are in [`templates/`](templates/).
- Turn the `Categories:` line at the bottom of each page into `[[Category:...]]` tags.
- Page names with hyphens become page names with spaces (`Rock-Warrior` → `Rock Warrior`).

## Contributing

Edit the Markdown here and open a pull request, the same as for code. Keep changes to one topic
per pull request where possible, and update the "Data sources" note on a page if you change a
number on it.
