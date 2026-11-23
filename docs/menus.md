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

**A replaced queue is offered back.** The collection tap is the one gesture that can throw away a
queue the user lined up, so `PlaybackDriver.play` keeps the outgoing queue for
`QUEUE_REPLACED_UNDO_MS` and the bar shows `Queue replaced | Undo` (`queueReplacedActions`). Undo
puts the snapshot back and resumes the item that was playing at the offset it left off; the album
track stays in the history it earned. The offer outranks the now-playing confirmation, because it
expires and the confirmation is decoration, and a notice still outranks it both. It is withdrawn
by any other play, by dismissing the bar, or when its timer runs out, so a stale snapshot can
never undo a newer choice. A tap whose queue had nothing upcoming has lost nothing and leaves the
bar alone.

## Known gaps

- Some screens still publish no options and show the grey "No options here" button for a moment:
  Settings, Audio quality, Appearance, Sleep timer, About, Keypad settings, Sign in, the service picker,
  New playlist, Add to playlist, Notes, Artwork. Empty options are only defensible for transient
  states (nothing playing, queue reordering).
- "Now playing" is an option on several screens but absent from Home and from every Settings-like
  screen. There is no written rule yet for what belongs in Options.
