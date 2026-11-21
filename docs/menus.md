# Options and row action menus

Sidetrack has one menu primitive: the **Options bar**. A screen publishes a `List<SideAction>`
through `SidekitScaffold(options = …)`; Sidekit's `ChromeState` swaps the action bar to that list
when the user presses the keypad's Options key (or holds it to focus the bar). There are no
Material menus, dropdowns or sheets in the app.

Row gutters publish `RowAction`s into the same action bar.

## Rules

**Shape.** A destination is a wide button with its label and glyph. An icon-only square is either
a list-wide toggle or one of Now Playing's shortcuts, where the bar is read as transport beside
the tiles. Sleep timer is a labelled destination on Queue and a square shortcut on Now Playing,
because three squares and the close square leave no width for a label there.

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

**Row action shape follows the count**: three or more actions are icon-only squares, one or two
keep their label, because a contextual bar earns its space from the gesture that opened it. This
is the one deliberate difference from the Options bar.

## Known gaps

- Some screens still publish no options and show the grey "No options here" button for a moment:
  Settings, Audio quality, Appearance, Sleep timer, About, Keyboard, Sign in, the service picker,
  New playlist, Add to playlist, Notes, Artwork. Empty options are only defensible for transient
  states (nothing playing, queue reordering).
- "Now playing" is an option on several screens but absent from Home and from every Settings-like
  screen. There is no written rule yet for what belongs in Options.
- The onboarding card teaches that Options opens About, which is not true on the service picker
  that shares the same "Welcome" title.
