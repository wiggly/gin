# Spec: cards you can see and click

The table draws cards as pictures, and a player plays with the mouse. A player selects a card in
their hand and then acts on it. They draw by clicking the stock or the upcard. They put their hand
in any order they like.

This builds on [the web UI spec](SPEC-WEB-UI.md), and this document states only what changes. [The
plan](PLAN-CARD-TABLE.md) states the files, the tests and the order of the work.

## What changes for the player

| Today | After this |
| --- | --- |
| A card is a line of text in a box | A card is a picture: rank and suit in two corners, a large suit in the middle |
| The other player's hand is a sentence | It is a fan of card backs, one per card held |
| The stock is a number | It is a card back with the number under it |
| Every legal move is a button | Drawing is a click on the stock or the upcard. Discarding is a click on a card, then a button |
| The hand is grouped as the server arranges it | The hand starts that way. The player sorts it, or drags cards into any order |
| Melds are rows | Melds are marked by a colored edge on each card, wherever the card sits |

Pass stays a button, because there is nothing to click for it.

## Client state

The web UI spec settled that the model holds the `PlayerView` unchanged and nothing the server
does not have. This work adds the first state that belongs to the browser alone: the order of the
hand, the selected card and the card being dragged. The server never sees any of it, and none of
it changes what a player holds or which moves are legal.

A round in play gets its own case of `Model`, because only a round in play has a hand to lay
out. The other seated views keep the case they have, narrowed to the views that have no hand.

```scala
case Seated(
    credentials: Credentials,
    view: PlayerView.AwaitingOpponent | PlayerView.Over,
    error: Option[String]
)
case Playing(
    credentials: Credentials,
    view: PlayerView.InPlay,
    holding: Holding,
    error: Option[String]
)
```

A model in play with no holding, or a holding with no round, cannot be built. The view stays
exactly as the server sent it.

The type is `Holding` rather than `Hand`, because `core` already has a `Hand`, and `View` imports
all of `core.domain`.

```scala
final case class Holding private (
    order: List[Card],
    selected: Option[Card],
    dragging: Option[Card]
)
```

The constructor is private. These are the only ways to make or change a `Holding`:

| Operation | What it does |
| --- | --- |
| `Holding.of(view)` | The server's order, melds first and then deadwood. Nothing selected or lifted. |
| `holding.reconcile(view)` | Keeps the player's order for the cards still held, drops cards no longer held, and puts new cards last. If the selected card is still a legal discard, keeps the selection. If the lifted card is still held, keeps it, because the other player's moves arrive while a card is in the air. |
| `holding.sorted(by, view)` | Puts the whole hand in the order `by` names. `ByMelds` reads the melds from `view`. |
| `holding.moved(before)` | If no card is lifted, does nothing. Otherwise puts the lifted card directly before `before`, or last for `None`, and clears the lifted card. |
| `holding.lifted(card)` | If the player holds the card, records it as the card being dragged. |
| `holding.toggled(card, view)` | If the card is selected, deselects it. If it is a legal discard in `view`, selects it. Otherwise does nothing. |

Together these hold three invariants. No single field can carry them, so the tests state them as
properties:

1. `order` holds exactly the cards in the view's arrangement, each once.
2. When `selected` holds a card, `Moves.available` offers that card as a `Discard`.
3. When `dragging` holds a card, that card is in `order`.

`Sorting` is an enum with three cases. `ByMelds` is the server's arrangement, `BySuit` groups by
suit and then orders by rank, and `ByRank` orders by rank and then by suit.

## Messages and update

`Msg.Play(move)` stays the only message that reaches the server. The stock, the upcard and the
action buttons all send a `Play` whose move comes from `Moves.available`. The rule of which moves
are legal is therefore still stated once, in `core`.

Five new messages change only the hand:

| Message | Sent by | Update |
| --- | --- | --- |
| `Toggle(card)` | A click on a card in the hand | `holding.toggled(card, view)` |
| `Sort(by)` | A sort button | `holding.sorted(by, view)` |
| `Lift(card)` | `dragstart` on a card in the hand | `holding.lifted(card)` |
| `Drop(before)` | `drop` on a card, or on the end of the row | `holding.moved(before)` |
| `Hover` | `dragover` on a drop target | Nothing. It exists because a handler has to return a message |

The existing messages change as follows:

| Message | Update |
| --- | --- |
| `Saw(view)` for a round in play, from `Connecting` or `Seated` | `Playing`, with `Holding.of(view)` |
| `Saw(view)` for a round in play, from `Playing` | `Playing`, with `holding.reconcile(view)`. A new deal starts from `Holding.of(view)` instead. A new deal is recognised by its stock: within a round the stock only shrinks, so a view with a larger stock than the current view is a new deal |
| `Saw(view)` for any other view | `Seated`, and any holding is dropped |
| `Play(move)` | The hand is unchanged. The next `Saw` settles it |
| `Failed(reason)` | The hand is unchanged, so a refused discard keeps its selection |

The five new messages do nothing in any state other than `Playing`. The existing catch-all case in
`Update` already gives that.

## Drawing a card

A new object, `Cards`, draws a card as inline SVG with Tyrian's `SVGTags`. `View` calls it in every
place that draws a card today.

- A face is a white rounded rectangle with a view box 70 wide and 100 high. The rank and the suit
  symbol sit in the top left. The same index, turned through 180 degrees, sits in the bottom right.
  One large suit symbol sits in the middle.
- No face has pip layouts or court art. The corner index carries the meaning, and a plain middle
  keeps the drawing code short.
- Hearts and diamonds use `--red`, and clubs and spades use `--ink`, as they do today.
- A back is the same rectangle with a plain repeating pattern.
- Every face has `role="img"` and an `aria-label` that names the card, for example "nine of
  spades". A back is labelled "a face-down card".

## The table

From top to bottom, the table shows these rows:

1. The scoreboard, as today.
2. The other player's hand, as `opponentSize` backs that overlap.
3. The stock, as one back with its count under it, beside the upcard and the pile count.
4. Your hand, in the order `holding.order` gives, with the sort buttons and the deadwood count
   under it.
5. The action buttons.
6. The round before, and any error, as today.

A hand of eleven cards does not fit the 44rem column at full width. Cards in a hand overlap by
about a third of their width, and the column stays as it is.

Each card in a meld has a colored bottom edge, and each meld in the arrangement has its own color.
A hand holds at most three melds, so the stylesheet defines three tints. Deadwood has no edge. The
deadwood count stays.

## What a player can click

If a thing leads to a move that `Moves.available` offers, or to a change of the hand, it is
clickable. Nothing else is clickable. A clickable card is a `button` that wraps its SVG, so the keyboard
reaches it too.
Clickable things show a pointer and lift a little on hover.

| Thing | Clickable when | Sends |
| --- | --- | --- |
| The stock | `DrawStock` is available | `Play(DrawStock)` |
| The upcard | `DrawDiscard` is available | `Play(DrawDiscard)` |
| A card in the hand | `Discard(card)` is available | `Toggle(card)` |
| A sort button | Always, while seated in play | `Sort(by)` |

The selected card stays lifted and has an outline. In a discard phase the action buttons are:

- With a card selected, "Discard 9♠". When `Knock` of that card is available, also "Knock with
  9♠".
- With nothing selected, one disabled button that reads "Select a card to discard".

The card taken from the pile this turn is never selectable, because `Moves.available` does not
offer it as a discard.

## Dragging

A player drags a card in their hand in any phase, on either player's turn, because the order
belongs to them alone. `dragstart` sends `Lift`. A drop on another card sends `Drop(Some(that
card))`. A drop on the space after the last card sends `Drop(None)`.

The `dragover` handler must call `preventDefault`, or no drop happens. Tyrian's source settles
this: every Tyrian `Event` calls `preventDefault` unless it is told not to. The same default
cancels a drag at `dragstart`, so that one handler uses `noPreventDefault`. Firefox starts a drag
only after `dataTransfer.setData`, so the `dragstart` handler calls it.

What remains is how real browsers behave, and only a person at a browser can see that. The drag
task ends with that check in Firefox and in Chrome. If dragging does not work there, it moves out
of this work and the sort buttons stay. A pointer-event version is a separate piece of work.

The only motion is a CSS transition on the lift. Animation stays out, as the web UI spec has it.

## Testing

`Holding` is pure, so a new `HoldingSuite` tests it with properties. It reads in-play views from the
generators in `core`'s tests, which `ui` already reaches through `protocol`'s test edge.

- Every operation keeps invariant 1.
- `reconcile` keeps the relative order of the cards that stay, and puts new cards last.
- `toggled` and `reconcile` keep invariant 2. `lifted` and `moved` keep invariant 3.
- Toggling a selectable card twice gives back the hand it started from.
- `moved(Some(t))` puts the lifted card directly before `t`.
- `sorted(ByMelds, view)` equals `Holding.of(view).order`.

`UpdateSuite` gets one case for each new message, one for `Saw` keeping a custom order, and one for
each new message arriving outside `Playing`.

`ViewSuite` pins these by reading the rendered value:

- Every card in the hand has its `aria-label`.
- When the move behind the stock, the upcard or a hand card is not available, it is not clickable.
- When a knock is not available, "Knock with" does not appear.
- A meld card carries its tint class, and a deadwood card carries none.

Coverage stays off for Scala.js targets, as the web UI spec records, so no floor applies to `ui`.
The properties above are the check on `Holding`.

## Decisions settled up front

| Decision | Choice | Why |
| --- | --- | --- |
| Where the card art comes from | SVG drawn in code | No asset files and no licence to track, and the view tests can read it. |
| How a discard is chosen | Select a card, then press a button | A stray click does not cost a card, and a knock stays a choice the player makes. |
| How a player draws | A click on the stock or the upcard | The pile is the thing a player reaches for. |
| Whether the hand can be reordered | Yes, with sort buttons and dragging | The player asked for it. The order is the browser's own state, so it changes nothing the server knows. |
| Where that state lives | A `Holding` beside the view, in a new `Model.Playing` | One type holds the invariants, and the view stays exactly as the server sent it. |
| How melds show in a custom order | A tint per meld | The grouping still tells the player what scores, wherever the cards sit. |

## Where the code came out different

Everything above is what landed, with these exceptions.

| The spec said | The code does | Why |
| --- | --- | --- |
| The "Testing" list names the `Holding` properties | The code also has the generators `ViewGen.discarding` and `ViewGen.discardTurn`, built by drawing rather than filtering. Its generators fail loudly on a move the rules refuse | A filtered generator starved the properties. A swallowed refusal hid disagreements between `Moves.available` and the rules. |
| [The plan](PLAN-CARD-TABLE.md), Task 5 Step 5, names a Firefox fallback for a button inside a draggable slot | The code does not apply it yet | It changes keyboard semantics. A person has to see the bug in Firefox before it is needed. |
| Nothing about a finished match in the update tests | `Saw` of a finished match while playing is covered by code and not by a property | The tests in `core` have no generator for a finished match. |

## Known gaps and follow-ups

The reviews of this work left these items open. Each one stands alone, so you can take it up in
a separate session. The number is a name for the item, not a priority.

### 1. The browser checks nobody did

Nobody looked at this work in a browser. The plan's Status line names the four steps: Task 3
Step 8, Task 4 Step 6, Task 5 Step 5 and Task 6 Step 1. Do them before you rely on the table.

- The faces show their corners and their middle suit. Red suits are red, and the backs show the
  weave.
- "The pile is empty" fits its card-sized box in `gin.css`.
- A click on a pile draws. A click on a hand card selects it, and a second click puts it down.
- A card dropped on another card lands directly before it. A card dropped past the last card
  lands last, and the end of the row does not cover the last card.
- In Firefox, a drag starts from a selectable card. If it does not, apply the fallback in
  [the plan](PLAN-CARD-TABLE.md), Task 5 Step 5, and remove its row from the table above.

### 2. A new deal is missed after a full-stock view

`Update.seat` in `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala` compares stock sizes. A
larger stock means a new deal. A round can end by a knock or gin after a draw from the pile only. In that
case the last view the player saw can hold a full stock, and the new deal holds the same number.
The update then reconciles, and cards that the two hands share keep the old custom order.

A reliable fix needs a deal number on `PlayerView.InPlay`. That changes the protocol, so it
needs the codec, the redaction tests and this spec to agree. Then `seat` compares deal numbers,
and `ViewGen` gains a pair at equal stock that crosses a deal.

### 3. A cancelled drag leaves a card lifted

Nothing handles `dragend` in `View.slot`. If the player cancels a drag with Escape or drops
outside the hand, `Holding.dragging` keeps the card. A later drop of anything on the hand, for
example selected text, then moves that card.

The fix has four parts. Add a `Msg` for `dragend` and a `Holding` operation that clears
`dragging`. Add a row to the messages table above, and a property to `HoldingSuite`.

### 4. The upcard button hides the card's name

The upcard button in `View.piles` has the label "Take the upcard". That label replaces the card's
own name, so a screen reader announces the action and not the card. Put both in the label, for
example "Take the nine of spades".

### 5. The other hand reads as many images

`View.opponent` draws one back for each card, and each back is an image with the label "a
face-down card". A screen reader reads it once per card. Give the fan one label, for example "The
other player holds 10 cards", and hide the backs inside it from assistive technology.

### 6. Hand slots have no keys

Snabbdom patches the slots in `View.hand` by position. After a drop or a sort, the `selected`
class moves from one element to another, and the lift transition plays on the wrong card. Give
each slot a key from its card.

### 7. The hover lift has no transition

`.pick:hover` in `gin.css` lifts at once, while `.slot` lifts with a transition. Add the same
transition to `.pick`, or decide that the instant lift is right and say so in this spec.

### 8. Test fixtures are copied across suites

`seat`, `waiting`, `chosen`, the view generators and the `Show` givens appear in more than one of
`HoldingSuite`, `UpdateSuite` and `ViewSuite`. The `chosen` in `ViewSuite` returns an `Option`,
and the others return a `Holding`. Move them into one test object in `modules/ui/src/test`.

### 9. The ui property tests are slow

On Scala.js, a property in the `ui` suites takes one to two minutes from cold, and the gate takes
three to five minutes. The cost of linking the tests and the cost of generation are not yet known
apart. Measure a warm `sbt ui/test` first. If generation is the larger cost, give the `ui` suites
one shared `checkConfig` with fewer successful runs.

### 10. Small test gaps

None of these caused a fault. Each one leaves a plausible fault with no failing test.

- Nothing tests that hearts and diamonds draw red and that clubs and spades draw black. Add a
  `pureTest` in `CardsSuite` that renders `Cards.face` and reads its class.
- A swap of the stock and upcard handlers fails no test. Pair each click with its pile.
- The test of a selected card rarely reaches a legal knock. Make `chosen` prefer a card that has
  a knock.
- Nothing runs the body of the `dragstart` handler, because Node has no `DragEvent`. Only the
  browser check in item 1 covers it.
- `ViewGen.stockDrawn` in `modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala` fails on a
  rare walk: a round that ends before any draw from the stock. The error names the game.

## Deliberately left out

- No pip layouts and no court art. The corner index is enough to play.
- No animation past the lift on hover and on selection.
- No pointer-event dragging. If HTML drag and drop does not work in Tyrian, the sort buttons stay.
- The order of a hand is not saved. A reload starts from the server's arrangement.
- No layout work past the single column. The overlap is the whole answer to width.
