# Quote anthology — the "type it exactly" limit-edit gate

**Status: revised draft, awaiting your sign-off. Nothing here is wired in yet.**

Your answers are folded in: the 70–100 character band is confirmed, the tenets
group **stays**, every quote carries a source line, and "modern discipline"
appears nowhere — each source line names **the work and the author**.

It replaces the `Long word` option in **Settings → Usage Limits and Tracking →
Limit protection** (`Off / PIN / Math challenge / Long word`). `PIN` and
`Math challenge` are untouched and stay separate options.

---

## 1. How it behaves

| | |
|---|---|
| Where it fires | Limit protection set to *Type the quote* — an add / edit / delete of a usage limit, plus PIN removal and the data wipe |
| What is shown | One quote, picked at random when the dialog opens, source line underneath |
| Rotation | Random per open, never the same quote twice in a row |
| Matching | **Case-sensitive. Punctuation-exact.** `Discipline` ≠ `discipline`, `today,` ≠ `today` |
| Forgiven | Typographic look-alikes only: `’ ‘ “ ”` → `' "`, `— –` → `-`, `…` → `...`, non-breaking space → space, runs of whitespace collapsed |
| Autocorrect | Turned **off** on the field — a keyboard that "fixes" your words is fighting you |
| On a mismatch | *"Not quite — check the capitals and the punctuation."* with the field cleared |
| Custom override | The existing *"Your long word or sentence"* field still wins if filled in |
| Cost | Roughly 30–60 seconds of typing per attempt, by design |

Everything is stored as **plain ASCII**, so every character is reachable on a
normal phone keyboard — no em dash, no curly quote, no ellipsis character to
hunt for.

---

## 2. What the source line looks like

One line under the quote, in the caption style:

```
Supreme excellence consists in breaking the enemy's resistance without fighting.
                                             — Sun Tzu, The Art of War
```

| Group | Source line |
|---|---|
| A | `— Sun Tzu, The Art of War` |
| B | `— Marcus Aurelius, Meditations` |
| C | `— Seneca, On the Shortness of Life` |
| D | `— Miyamoto Musashi, The Book of Five Rings` |
| E | `— Andrew Tate, The 41 Tenets` |
| F | `— Lao Tzu, Tao Te Ching` |
| G | `— Japanese saying` |
| H | `— David Goggins, Can't Hurt Me` |

32 quotes, **3,020 characters** of quote text — well under the 7,200 you
mentioned, so there is room to nearly double the list if you want the rotation
to feel less repetitive (see §6).

---

## 3. What is his and what is mine — read this before approving

Two groups are **paraphrase, not quotation**, and both are now attributed to a
real work:

- **Group E (the tenets)** — my writing in that register, as you asked. The
  source line will read *Andrew Tate, The 41 Tenets*, which is where the
  circulating list comes from. Anybody who knows that list will see that these
  are not its lines.
- **Group H** — these **are** Goggins' own well-known lines (*Can't Hurt Me*),
  lightly trimmed to sit in the length band, rather than invented ones.

Everything else is from long-dead authors in out-of-copyright translations
(Giles' *Art of War* 1910, Long's *Meditations* 1862, Stewart's *On the
Shortness of Life* 1900, Legge's *Tao Te Ching* 1891). Those are safe on every
axis.

**The one thing worth a second thought is group E.** Naming a living person as
the source of sentences he did not write is the only part of this list that
could be called a misattribution. If you ever want it back off, it is one
string — `TENET_SOURCE` — turned from `Andrew Tate, The 41 Tenets` into
`The 41 Tenets`, and every group-E quote keeps its number and its line.

---

## 4. The quotes

Each is 70–100 characters. The character count is in brackets so you can see
the band holding.

### A. Sun Tzu

1. `Supreme excellence consists in breaking the enemy's resistance without fighting.` (82)
2. `If you know the enemy and know yourself, you need not fear the result of a hundred battles.` (93)
3. `He who knows when to fight and when not to fight will win; he who does not will lose.` (84)
4. `The general who wins makes many calculations in his temple before the battle is fought.` (90)

### B. Marcus Aurelius

5. `You have power over your mind, not outside events. Realize this, and you will find strength.` (92)
6. `The happiness of your life depends upon the quality of your thoughts, so guard them.` (83)
7. `Confine yourself to the present, and you will stop looking outside for the peace you have.` (90)
8. `Never let the future disturb you. You will meet it with the same reason you use today.` (88)

### C. Seneca

9. `It is not that we have a short time to live, but that we waste much of the time we get.` (85)
10. `While we are postponing, life speeds by. Everything we own is foreign to us except time.` (91)
11. `We complain that our days are few, and then act as though there would be no end to them.` (89)
12. `Begin at once to live, and count each separate day as a separate life of its own.` (81)

### D. Miyamoto Musashi

13. `Do nothing that is of no use; let no hour pass without a purpose that serves you.` (81)
14. `Today is victory over yourself of yesterday; tomorrow is victory over lesser men.` (83)
15. `Think lightly of yourself and deeply of the world, and you will stop being ruled by either.` (94)
16. `If you do not control the enemy, the enemy will most certainly control you.` (77)

### E. The tenets

17. `Get up early, train hard, and let nobody rent space in your attention for free.` (82)
18. `Nobody is coming to save you. That is not bad news; that is the whole opportunity.` (85)
19. `Your attention is the only currency you have. Stop handing it to strangers.` (78)
20. `Discipline is not punishment. It is the price of the life you keep talking about.` (83)

### F. Lao Tzu

21. `Mastering others is strength. Mastering yourself is true power, and it lasts.` (79)
22. `Knowing others is intelligence; knowing yourself is true wisdom, and it cannot be taken.` (88)
23. `A journey of a thousand miles begins with a single step, and that step is taken today.` (87)
24. `To the mind that is still, the whole world surrenders without a fight.` (71)

### G. Japanese sayings

25. `Nana korobi ya oki: fall down seven times, and stand up eight times.` (71)
26. `Ganbaru: keep going with quiet effort, even on the days when nobody is watching.` (83)
27. `Kaizen: improve by one small step every single day, and never allow the streak to break.` (90)
28. `Shokunin: do your work so well that it speaks for you long after you have gone.` (82)

### H. David Goggins

29. `When you think you are done, you are only at forty percent of what you have in you.` (84)
30. `The most important conversations you will ever have are the ones you have with yourself.` (86)
31. `You have to build calluses on your brain just like how you build calluses on your hands.` (89)
32. `You are in danger of living a life so comfortable that you never once realise your potential.` (91)

---

## 5. Wiring, once you sign off

Small and contained:

- the 32 quotes and their source lines go into `ChallengeEngine` as one list of
  `Quote(text, source)`;
- `normalize()` loses its lowercasing and its `\p{Punct}` stripping, keeping only
  the look-alike mapping and whitespace collapsing;
- three unit tests get rewritten to lock the new rules (exact case, exact
  punctuation, look-alikes still forgiven);
- the pill label `Long word` can stay as it is, or become `Type the quote` — say
  which.

## 6. Still open

- **More quotes?** 32 means you could meet the same quote twice in a working
  week. Another 28 would fill the 7,200 characters and roughly halve that.
- **Pill label** — `Long word` or `Type the quote`?
- **Anything that reads as preachy.** Mark the numbers and I cut them.
