# Style Guide

How to write and edit pages on this wiki. The goal is a wiki that is accurate, easy to read,
and easy to move between wiki hosts.

## Voice

- Write for a player, not a programmer. Explain what something does in the game before
  explaining the formula.
- Use plain words and short sentences. Prefer "use" to "utilize", "more" to "additional".
- Use the game's own names, capitalised as the game does: Quarters, Armory, Tower, Ship,
  Peon, Rock Warrior, Chieftain, Terrifying Toot, Map Mode.
- Spell it "Armory" (the in-game spelling). Elsewhere either British or American spelling is
  fine; keep one per page.
- Refer to unit types and characters without guessing at pronouns unless the game's text
  gives them.

## Page structure

1. `# Title`, matching the file name (`Rock-Warrior.md` → `# Rock Warrior`).
2. For units, buildings, spells and campaign islands: an **infobox** table straight after the
   title (see [Infoboxes](#infoboxes)).
3. A one-paragraph summary that names the subject in **bold**.
4. Sections with `##` headings. Don't skip heading levels.
5. Optional `## Tips` and `## Notes for editors` sections.
6. `## Data sources`: the source files where the numbers come from.
7. A last line: `Categories: Area · Subarea`.

Use the templates in `templates/` to start a new page.

## Links

- Link with relative paths and the `.md` extension: `[Peon](Peon.md)`,
  `[Stun](Magic.md#terrifying-toot)`.
- Section anchors are the heading in lower case, with spaces turned into hyphens and
  punctuation removed.
- Link the first mention of another page's subject in each section, not every mention.
- Never use a bare URL to another wiki page; it breaks when the wiki moves.

## Infoboxes

An infobox is a two-column table. The header row is the page title and an empty cell; each
row after that is a **bold** field name and a value. Use the same field names, in the same
order, on every page of a type, so they can be turned into wiki templates later. The field
lists are in the templates:

| Page type | Template |
|---|---|
| Unit | `templates/Unit.md` |
| Building | `templates/Building.md` |
| Spell | `templates/Spell.md` |
| Campaign island | `templates/Campaign-Island.md` |
| Guide | `templates/Guide.md` |

## Numbers

- **Every game statistic must come from the game.** Take it from the source code, not from
  memory or an old forum post, and list the source files under *Data sources*.
- Quote times at Normal game speed.
- Give hit chances and defense as percentages (`75%`), and bonuses as percentage points.
- Speeds and distances are in the game's own units; say so on first use in a page.
- When a balance patch changes a number, update every page that quotes it. Search the wiki for
  the old value.

## Original versus Resurrected

Mark features that the original 2004 game didn't have with *Resurrected* the first time they
come up on a page, for example: "[Ships](Ship.md) (*Resurrected*)". Pages entirely about such a
feature start with a one-line italic note.

## Uncertain facts

- Mark anything not yet confirmed with *(needs source)* or put it under
  `## Notes for editors`.
- For things worked out from the code but not checked in play, add
  "*Community: please confirm this in play.*"
- Don't delete someone else's uncertain note without confirming or correcting it.

## Formatting rules for portability

These keep pages working on every wiki engine:

- GitHub-flavored Markdown only: headings, lists, tables, bold, italics, links, inline code
  and fenced code blocks.
- No raw HTML, no front matter, no footnotes, no engine-specific macros or templates.
- Images: stored in an `images/` folder and embedded with standard Markdown image syntax
  (an exclamation mark, the alt text in square brackets, then `images/file-name.png` in
  parentheses). Write alt text that describes the picture.
- Keep table cells short. Long explanations go in the text below the table.

Categories: Wiki upkeep
