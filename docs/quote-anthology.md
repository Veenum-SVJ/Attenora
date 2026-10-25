# Quote anthology — the "type it exactly" limit-edit gate

**Status: retired in v1.2.1.** The 60 attributed quotes below were replaced by
twelve neutral awareness passages with no author line (StayFree's own
"Awareness Text Challenge" has no attribution either), and the option is now
called **Type the passage**. The behaviour documented here — case-sensitive,
punctuation-exact retyping with only typographic look-alikes forgiven — is
unchanged. Kept for the record.

**v1.2.2:** the third option, `PIN`, was removed. Limit protection is now
`Off / Math challenge / Type the passage`; anyone who had PIN protection set is
migrated to the passage challenge rather than left unprotected.

**v1.2.2, second pass:** the twelve in-house passages were replaced by
**StayFree's own pool** — all 104 English `alarm_screen_blocked_text` strings
extracted from the StayFree 20.11.0 APK (the same pool its block screen and its
Awareness Text Challenge draw from), with decorative curly quotes stripped and
the rest ASCII-fied. They range from 14 to 216 characters, no attribution, and
live in `ChallengeEngine.quotes`. The dialog's input is now a growing
multi-line box instead of a single scrolling line.

60 quotes, all attributed to **the work and the author**, 70–100 characters,
plain ASCII. It replaced the `Long word` value in **Settings → Usage Limits and
Tracking → Limit protection** (`Off / Math challenge / Type the passage`).

---

## 1. How it behaves

| | |
|---|---|
| Where it fires | Limit protection set to *Type the quote* — an add / edit / delete of a usage limit |
| What is shown | One quote picked at random when the dialog opens, source line underneath |
| Rotation | Random per open from all 60 |
| Matching | **Case-sensitive. Punctuation-exact.** `Discipline` ≠ `discipline`, `today,` ≠ `today` |
| Forgiven | Typographic look-alikes only: `’ ‘ “ ”` → `' "`, `– —` → `-`, `…` → `...`, non-breaking space → space, runs of whitespace collapsed |
| On a mismatch | *"It doesn't match yet"* with the field cleared |
| Custom override | **Removed.** The old *"Your long word or sentence (optional)"* field is gone — the rotation is the whole feature |
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
| C | `— Seneca, On the Shortness of Life` / `— Seneca, Letters to Lucilius` |
| D | `— Miyamoto Musashi, The Book of Five Rings` |
| E | `— Andrew Tate, The 41 Tenets, Tenet 1` … `Tenet 9` |
| F | `— Lao Tzu, Tao Te Ching` |
| G | `— Japanese saying` |
| H | `— David Goggins, Can't Hurt Me` |

60 quotes, **4,941 characters** of quote text.

---

## 3. What is his and what is mine — read this before approving

Two groups are **paraphrase, not quotation**, and both are attributed to a real
work:

- **Group E (the tenets)** — my writing in that register, as you asked, numbered
  `Tenet 1` … `Tenet 9` so the source line says exactly which one is being
  retyped. Anybody who knows the circulating list will see that these are not
  its lines.
- **Group H** — these **are** Goggins' own well-known lines (*Can't Hurt Me*),
  lightly trimmed to sit in the length band, rather than invented ones.

Everything else is from long-dead authors in out-of-copyright translations
(Giles' *Art of War* 1910, Long's *Meditations* 1862, Stewart's *On the
Shortness of Life* 1900, Legge's *Tao Te Ching* 1891). Those are safe on every
axis.

**The one thing worth a second thought is group E.** Naming a living person as
the source of sentences he did not write is the only part of this list that
could be called a misattribution. If you ever want it back off, every group-E
source line is one edit: drop `Andrew Tate, ` and keep the tenet number.

---

## 4. The quotes

Each is 70–100 characters. The character count is in brackets so you can see
the band holding.

### A. Sun Tzu

1. `Supreme excellence consists in breaking the enemy's resistance without fighting.` (80)
2. `If you know the enemy and know yourself, you need not fear the result of a hundred battles.` (91)
3. `He who knows when to fight and when not to fight will win; he who does not will lose.` (85)
4. `The general who wins makes many calculations in his temple before the battle is fought.` (87)
5. `All warfare is based on deception. When able to attack, we must seem unable.` (76)
6. `The victorious strategist seeks battle only after the victory has already been won.` (83)
7. `Be swift as the wind, quiet as the forest, and immovable as the mountain.` (73)

### B. Marcus Aurelius

8. `You have power over your mind, not outside events. Realize this, and you will find strength.` (92)
9. `The happiness of your life depends upon the quality of your thoughts, so guard them.` (84)
10. `Confine yourself to the present, and you will stop looking outside for the peace you have.` (90)
11. `Never let the future disturb you. You will meet it with the same reason you use today.` (86)
12. `Do every act of your life as though it were the very last act of your life.` (75)
13. `Waste no more time arguing about what a good man should be. Just be one, now.` (77)
14. `The soul becomes dyed with the colour of its thoughts, so choose them carefully.` (80)
15. `Look well into yourself; there is a source of strength which will always spring up.` (83)

### C. Seneca

16. `It is not that we have a short time to live, but that we waste much of the time we get.` (87)
17. `While we are postponing, life speeds by. Everything we own is foreign to us except time.` (88)
18. `We complain that our days are few, and then act as though there would be no end to them.` (88)
19. `Begin at once to live, and count each separate day as a separate life of its own.` (81)
20. `Nothing is ours except time, and it is the one thing that will not wait for us.` (79)
21. `Let us prepare our minds as if we had come to the very end of life, and waste none of it.` (89)
22. `Life is long if you know how to use it; most of it is spent on doing nothing at all.` (84)
23. `Each day should be regulated as if it closed the series, and completed the whole account.` (89)

### D. Miyamoto Musashi

24. `Do nothing that is of no use; let no hour pass without a purpose that serves you.` (81)
25. `Today is victory over yourself of yesterday; tomorrow is victory over lesser men.` (81)
26. `Think lightly of yourself and deeply of the world, and you will stop being ruled by either.` (91)
27. `If you do not control the enemy, the enemy will most certainly control you.` (75)
28. `Perceive that which cannot be seen with the eye, and understand the spirit of the thing.` (88)
29. `You should not have a favourite weapon; use what the moment requires and nothing more.` (86)
30. `Study strategy broadly, and do not let your mind be drawn in one direction only.` (80)

### E. The tenets

31. `Get up early, train hard, and let nobody rent space in your attention for free.` (79)
32. `Nobody is coming to save you. That is not bad news; that is the whole opportunity.` (82)
33. `Your attention is the only currency you have. Stop handing it to strangers.` (75)
34. `Discipline is not punishment. It is the price of the life you keep talking about.` (81)
35. `You are not tired. You are undisciplined, and that is a problem you can actually fix.` (85)
36. `The phone is not the problem. Your willingness to be interrupted is the problem.` (80)
37. `Every hour you spend scrolling is an hour somebody else decided you would spend.` (80)
38. `Comfort is the most expensive thing you will ever buy, and you buy it daily.` (76)
39. `Do the boring thing today so that the hard thing is not waiting for you tomorrow.` (81)

### F. Lao Tzu

40. `Mastering others is strength. Mastering yourself is true power, and it lasts.` (77)
41. `Knowing others is intelligence; knowing yourself is true wisdom, and it cannot be taken.` (88)
42. `A journey of a thousand miles begins with a single step, and that step is taken today.` (86)
43. `To the mind that is still, the whole world surrenders without a fight.` (70)
44. `When you are content to be simply yourself, everybody will respect you for it.` (78)
45. `He who knows that enough is enough will always have enough, and will not be poor.` (81)
46. `Watch your thoughts; they become words, then actions, then the life you live.` (77)

### G. Japanese sayings

47. `Nana korobi ya oki: fall down seven times, and stand up again eight times.` (74)
48. `Ganbaru: keep going with quiet effort, even on the days when nobody is watching.` (80)
49. `Kaizen: improve by one small step every single day, and never allow the streak to break.` (88)
50. `Shokunin: do your work so well that it speaks for you long after you have gone.` (79)
51. `Ichigo ichie: this meeting will never happen again, so give it your full attention.` (83)
52. `Mottainai: waste nothing, because everything you have cost somebody something.` (78)

### H. David Goggins

53. `When you think you are done, you are only at forty percent of what you have in you.` (83)
54. `The most important conversations you will ever have are the ones you have with yourself.` (88)
55. `You have to build calluses on your brain just like how you build calluses on your hands.` (88)
56. `You are in danger of living a life so comfortable that you never once realise your potential.` (93)
57. `You are stopping when you are tired, not when you are done, and those are not the same.` (87)
58. `Nobody cares about your excuses, and neither should you. Get the work in today.` (79)
59. `The only way to get better is to do the thing you are avoiding, and then do it again.` (85)
60. `Stay hard when the easy thing is right there, because that is the whole test.` (77)

---

## 5. How it is wired

- `ChallengeEngine.quotes` is one `List<Quote(text, source)>` of the 60 lines.
- `ChallengeEngine.normalize()` keeps only the look-alike mapping and whitespace
  collapsing — no lowercasing, no `\p{Punct}` stripping.
- `matchesQuote(input, quote.text)` compares the normalised forms exactly.
- The dialog shows the quote, then `— <source>` under it, then
  *"Type it exactly. Capitals and punctuation count."*
- Four unit tests lock it down: exact case, exact punctuation, look-alikes still
  forgiven, and a data test asserting every quote is 70–100 characters, pure
  ASCII, credited, and matches itself.
- The pill reads `Type the quote`. The optional custom-sentence field is gone.

## 6. Still open

- **Anything that reads as preachy.** Mark the numbers and I cut them.
- **Group E attribution.** See §3 — say the word and `Andrew Tate, ` comes off
  every tenet source line.
