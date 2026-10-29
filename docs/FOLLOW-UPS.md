# Follow-ups

These are small pieces of work that the card table and the lobby left open. Each one stands alone, so you can take
it up in a separate session. The number is a name for the item, not a priority. When you finish an
item, delete it and leave the other numbers as they are.

The larger plans for the game are in the "What comes later" section of [the design
document](DESIGN.md). [The card table spec](SPEC-CARD-TABLE.md) says what these items build on.

## 1. A new deal is missed after a full-stock view

`Update.seat` in `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala` compares stock sizes. A
larger stock means a new deal. A round can end by a knock or gin after a draw from the pile only. In
that case the last view the player saw can hold a full stock, and the new deal holds the same
number. The update then reconciles, and cards that the two hands share keep the old custom order.

A reliable fix needs a deal number on `PlayerView.InPlay`. That changes the protocol, so it needs
the codec, the redaction tests and [the card table spec](SPEC-CARD-TABLE.md) to agree. Then `seat`
compares deal numbers, and `ViewGen` gains a pair at equal stock that crosses a deal.

## 3. The upcard button hides the card's name

The upcard button in `View.piles` has the label "Take the upcard". That label replaces the card's
own name, so a screen reader announces the action and not the card. Put both in the label, for
example "Take the nine of spades".

## 4. The other hand reads as many images

`View.opponent` draws one back for each card, and each back is an image with the label "a face-down
card". A screen reader reads it once per card. Give the fan one label, for example "The other player
holds 10 cards", and hide the backs inside it from assistive technology.

## 5. Hand slots have no keys

Snabbdom patches the slots in `View.hand` by position. After a drop or a sort, the `selected` class
moves from one element to another, and the lift transition plays on the wrong card. Give each slot a
key from its card.

## 6. The hover lift has no transition

`.pick:hover` in `gin.css` lifts at once, while `.slot` lifts with a transition. Add the same
transition to `.pick`, or decide that the instant lift is right and say so in [the card table
spec](SPEC-CARD-TABLE.md).

## 7. Test fixtures are copied across suites

`seat`, `waiting`, `chosen`, the view generators and the `Show` givens appear in more than one of
`HoldingSuite`, `UpdateSuite` and `ViewSuite`. The `chosen` in `ViewSuite` returns an `Option`, and
the others return a `Holding`. Move them into one test object in `modules/ui/src/test`.

## 8. The ui property tests are slow

On Scala.js, a property in the `ui` suites takes one to two minutes from cold, and the gate takes
three to five minutes. The cost of linking the tests and the cost of generation are not yet known
apart. Measure a warm `sbt ui/test` first. If generation is the larger cost, give the `ui` suites
one shared `checkConfig` with fewer successful runs.

## 9. Small test gaps

None of these caused a fault. Each one leaves a plausible fault with no failing test.

- Nothing tests that hearts and diamonds draw red and that clubs and spades draw black. Add a
  `pureTest` in `CardsSuite` that renders `Cards.face` and reads its class.
- A swap of the stock and upcard handlers fails no test. Pair each click with its pile.
- The test of a selected card rarely reaches a legal knock. Make `chosen` prefer a card that has a
  knock.
- Nothing runs the body of the `dragstart` handler, because Node has no `DragEvent`. Only a person
  at a browser covers it.
- `ViewGen.stockDrawn` in `modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala` fails on a rare
  walk: a round that ends before any draw from the stock. The error names the game.

## 10. The lobby board is written on every move

`Games.watch` in `modules/core/src/main/scala/wiggly/gin/core/service/Games.scala` taps the taken
write for every in-play state. Every move by a host writes to the board and wakes every lobby
watcher before `.changes` drops it. Stop tapping after the first game that is not waiting.

## 11. A name can look empty or reorder text

`PlayerName.from` in `modules/core/src/main/scala/wiggly/gin/core/domain/PlayerName.scala` trims
characters up to U+0020 only. A name of non-breaking or zero-width characters is accepted, and so
are bidi and other format characters. The lobby row isolates the name for now. Consider requiring
one visible character.

## 12. Anyone can fill the lobby

Anyone can create games and hold event streams open, and the list has no cap. See
`LobbyRoutes.scala` and `Lobbies.scala`. Decide on a cap for the list and for the streams.

## 13. Lobby test gaps

None of these caused a fault.

- `GameRoutesSuite`: the title that says it "creates nothing" claims more than the test shows.
- `LobbySuite`: the host is always "Ada", so nothing tests that a re-open keeps the host.
- `LobbyPresenceSuite`: the `agrees` check in the join test runs over an empty lobby.
- `LobbyPresenceSuite`: the guest stream test cannot tell a wrong listing from none.
- `LobbyRoutesSuite`: the opening test assumes the first chunk holds the whole event.
- `LobbyRoutesSuite`: no test shows that a change to the board sends a new list.
- `UpdateSuite`: the `JoinListed` test only checks that a command was sent. A wrong id passes.
- `ViewSuite`: the "waiting 2 min" test passes if any one row says it.
- `ViewSuite`: "scoreboard names both players" renders the whole table.
- `PlayerNameSuite`: the title says a control character "anywhere" refuses a name, but the
  generator puts one inside the name only. Space at the edges is trimmed, so say "inside".
- `MemoryLobbyBoardSuite`: the first test calls `change` before `watch`, so it does not show that
  nothing is lost while a watcher subscribes.
- `GinApiSuite`: the body limit test uses a stub route. No test sends an oversized body to the
  real create and join routes.
- `GameRoutesSuite` uses the real `MemoryLobbyBoard`, while every other port there is a fake.

## 14. Lobby polish

- `Lobby.Row` and `rows` could be `private[domain]`.
- The `ListedAt` codec comment does not name the case that it allows.
- `docs/openapi.yaml`: the `info.description` line is long, and `400` comes before the 2xx cases.
- The name input in `View.scala` has no `maxlength`.
- The age colour in `gin.css` is a literal.
- `Events.watch` in `modules/ui/src/main/scala/wiggly/gin/ui/Events.scala` has no braces. About
  20 other methods with a single expression have none either. Decide whether the braces rule in
  `CLAUDE.md` covers them, and then change all of them or none.
- `MemoryGameEventsSuite` has the `NameGen` import in a group of its own.
- `docs/SPEC-LOBBY.md`: the line about the breaking change for a body with no name can link to
  the `400` row. `docs/SPEC-WEB-UI.md`: the start screen row ends in a sentence fragment.

## 15. Two sessions in one browser start with the same name

`Profile` in `modules/ui/src/main/scala/wiggly/gin/ui/Profile.scala` keeps the name in
`localStorage`. Every tab and normal window on one origin shares it, so a second session opens
with the name that the first one typed. A private window has its own storage. Decide whether a
tab keeps its own name, for example with `sessionStorage` first and `localStorage` second.

## 16. A seat is not guarded against blocked storage

`Seat` in `modules/ui/src/main/scala/wiggly/gin/ui/Seat.scala` calls `sessionStorage` with no
guard. If a browser blocks storage, a player who arrives by a game link fails at `init`. `Profile`
wraps its calls in `Try`. Do the same in `Seat`.

## 17. The name is saved on every keystroke

`Main.update` calls `Profile.remember` for each `NameChanged`. This saves names that are half
typed or that the rule refuses. Save the name when a seat is asked for instead.

## 18. Coverage after the lobby fixes

The last coverage figure, 94.46% of statements and 92.20% of branches, is from before the final
lobby fixes. Run `sbt coverageAll` again and update the floors in `build.sbt` if they moved.

## 19. The earlier web-ui commits had no review of the whole branch

The final review of the lobby covered `a00d387..d8b1177` only. It did not cover the web UI and
card table commits before it. Review the whole `web-ui` branch against `main` before it merges.
