# Options and row action menus

Sidetrack has one menu primitive: the **Options bar**. A screen publishes a `List<SideAction>`
through `SidekitScaffold(options = …)`; Sidekit's `ChromeState` swaps the action bar to that list
when the user presses the keypad's Options key (or holds it to focus the bar). There are no
Material menus, dropdowns or sheets in the app.

Row gutters publish `RowAction`s into the same action bar.

## Rules

**Shape.** A destination is a wide button with its label and glyph. An icon-only square is either
a list-wide toggle, one of Now Playing's shortcuts, or a playlist-page edit that has run out of
width beside the queue destination. Sleep timer is a labelled destination on Queue and a square
shortcut on Now Playing, because three squares and the close square leave no width for a label
there. Rename and Delete are squares on a playlist page for the same reason: Add to queue owns the
one label, and three labels plus the close square would truncate all of them. Their content
descriptions still name the playlist, and Delete stays red and last.

**Tone.** Accent for every live option — "an option is live wherever it leads", so tone says what
it does, never how far it travels. Green (`Secondary`) for a toggle that is on, red
(`Destructive`) for destroy. Grey is left to the close square, notices, undo and Cancel.

**Order.** Destinations first, the list-wide control last, Now playing at the end. Destructive
entries sort last in both menus.

**Toggle state** is expressed twice: tone change *and* label/content description. A colour-only
state is invisible in a square button.

**Shared entries.** One builder per menu kind, one factory per shared entry, so label, icon, shape
and tone cannot drift per screen:

- Options: `ActionBars.kt` — `sidetrackOptions(...)` plus one factory per entry
  (`nowPlayingOption`, `settingsOption`, `aboutOption`, `newPlaylistOption`, `addToQueueOption`,
  `artistRadioOption`, `openQueueOption`, `songRadioOption`, `episodeNotesOption`,
  `sleepTimerOption`, `shuffleOption`, `sleepTimerShortcutOption`).
- Row actions: `RowActionBar.kt` — `rowActions(...)` plus one factory per entry.

`OptionsBuilderTest` pins the rules.

**Glyph.** No two entries in the same bar share an icon. Three squares drop their label, so the
symbol is the only thing left to tell them apart: `Add to queue` carries the queue glyph
(`QueueMusic`) and `Add to playlist` the list-with-plus one (`PlaylistAdd`), because the two sit
next to each other on album and radio rows.

**Row action shape follows the count**: three or more actions are icon-only squares, one or two
keep their label, because a contextual bar earns its space from the gesture that opened it. This
is the one deliberate difference from the Options bar.

**A row tap and a row action are two different plays.** On an album, playlist or radio page the
tap plays the collection from that row, so the tail becomes the rest of it. Those pages therefore
carry `Play` (`playRowAction`), which plays the row now and puts the previous item back at the head
of the tail: the queue resumes where it was once the picked track ends. Its label stays one word
because the square drops it; the content description says "Play <title> now".

**Every prompt is one bar: a decision on the left, a close square on the right.** There are three
kinds, told apart by tone, all built in `PromptBars.kt`:

- **Restore** — the edit is applied and can be taken back. Grey, `SidekitIcon.Undo`, rising in place
  (`ActionMotion.Up`), labelled with the inverse: "Restore queue", "Restore X", "Restore upcoming",
  "Restore history", "Restore search history", "Restore old name". Never the bare word "Undo": Undo
  is the mechanism, the button says the act. One verb everywhere — what comes back is always
  *restored*, so the button only has to name its object. Where the object is not the point the wording says so: the
  deferred playlist delete reads "Restore Road Trip" and its description adds that the deletion is
  cancelled.
- **Confirm** — the edit can never be taken back, so it is asked *before*: `Clear cache?`,
  `Sign out?`. Red (`ActionTone.Destructive`), the action keeps its own glyph, and the question
  mark is where "are you sure" lives now that Cancel has lost its word.
- **Notice** — nothing to decide, only to read: `Added track to Road Trip`, `Removed "night"`, or a
  failure. Grey wherever the kind is neutral, and the message is the slot.

The close square **never changes the world**. In a restore the edit stays; in a confirm nothing has
happened yet; in a notice it only goes away. That is why one glyph accepts in one bar and cancels in
the next: it always means "take this bar away". The cursor lands on it in all three, so Enter is
always the press that changes nothing, and Left is the one step to the decision — no walking the
list again. The Back key does the same and stays on the page; it never performs the prompt and never
leaves the screen. Labels stay English on purpose: every string in this app is, and taking Sidekit's
localised default would make one button change language under a screen that is not translated.

**Restore is for destructive edits only.** Removing, clearing or deleting something the user can see
is offered back; creation is not. Adding a track to a playlist reports "Added track to X" as a
notice, because the way back would itself be a destructive action wearing a safety label.

The bar shows one prompt at a time. A restore window is five *visible* seconds (`UndoWindow`, paused
while the screen is away); the queue-replaced offer is the one exception at eight, because it
outlives the screen that raised it. A confirmation is not a bar occupant but a mode: it outranks a
notice and pauses a live restore window rather than letting it burn behind the question.

Two restore semantics exist and stay named as such: most edits are applied immediately and the
restore replays the inverse; the playlist delete is deferred, so "Restore <title>" cancels a write
that has not happened yet, and the close square commits it. Removing from a playlist cannot be
restored by any provider, so it is a confirmation, not an offer (`provider-capabilities.md`).

Two edits deliberately raise nothing. A **queue reorder** ends with Done, so the mode's own commit
is the confirmation: an offer after it would ask the user to doubt a decision they just made twice.
**Removing one search term** is a notice, not an offer: the term is one keystroke to type again, and
the bar has already said it went. A **rename** does get an offer — "Restore old name" — because the
expensive part is retyping the name, not remembering it.

**A replaced queue is offered back.** The collection tap is the one gesture that can throw away a
queue the user lined up, so `PlaybackDriver.play` keeps the outgoing queue for
`QUEUE_REPLACED_UNDO_MS` and the bar shows `Restore queue | ✕` (`queueReplacedActions`). It is the
offer's usual shape, and the label does what the scene cannot: the replaced queue is nowhere on
screen, so the button names it. Undo puts the snapshot back and resumes the item that was playing
at the offset it left off; the album track stays in the history it earned. The offer outranks the
now-playing confirmation, because it expires and the confirmation is decoration, and a notice still
outranks it both. It is withdrawn by any other play, by the close square, by Back, or when its
timer runs out, so a stale snapshot can never undo a newer choice. A tap whose queue had nothing upcoming has lost nothing and leaves the bar alone.

## Known gaps

- Some screens still publish no options and show the grey "No options here" button for a moment:
  Settings, Audio quality, Appearance, Sleep timer, About, Keypad settings, Sign in, the service picker,
  New playlist, Add to playlist, Notes, Artwork. Empty options are only defensible for transient
  states (nothing playing, queue reordering).
- "Now playing" is an option on several screens but absent from Home and from every Settings-like
  screen. There is no written rule yet for what belongs in Options.
