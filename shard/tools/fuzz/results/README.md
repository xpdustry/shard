# Fuzz campaign reports

| File | Head | Command | Result |
|---|---|---|---|
| `gate-10M-cb81925d.txt` | `cb81925d` | `scripts/vm-gradle.sh :tools:fuzz:fuzz -PfuzzHeap=6g -Pargs="--packets 10000000 --maps maze,fortress,glacier,triad --clients 128 --frames 2 --seed 202"` on a `large` boat VM | 10,000,148 messages in 280 s, no invariant broken; 4 matches replayed, 204 hashes identical |

Earlier runs, before the fixes, found F1 (a null-type binary custom packet, `Ui.custom`) and F2 (a turret's block unit
with no controller kind, which failed fortress and triad at their next hash); both are fixed on this branch with tests.
