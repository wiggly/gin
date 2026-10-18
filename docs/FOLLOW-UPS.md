# Follow-ups

These are small pieces of work that the card table left open. Each one stands alone, so you can take
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

## 2. A cancelled drag leaves a card lifted

Nothing handles `dragend` in `View.slot`. If the player cancels a drag with Escape or drops outside
the hand, `Holding.dragging` keeps the card. A later drop of anything on the hand, for example
selected text, then moves that card.

The fix has four parts. Add a `Msg` for `dragend` and a `Holding` operation that clears `dragging`.
Add a row to the messages table in [the card table spec](SPEC-CARD-TABLE.md), and a property to
`HoldingSuite`.

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
