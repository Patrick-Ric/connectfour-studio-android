#!/usr/bin/env python3
"""Generate reference data from the Python engine (cfs_core + bitbully).

The Kotlin port must reproduce these numbers exactly: per-depth scores and
node counts of Engine.iterative_scores (same TT size/hash => same search),
MTD(f) values, opening-book values, scoreToMovesLeft, win cells and the
.4gp legal-prefix rule.

Run with the Python of the Qt version (bitbully==0.0.79 and
bitbully-databases==0.0.2 installed), without writing bytecode into the
source tree:

  PYTHONDONTWRITEBYTECODE=1 "<Qt dir>/.venv/bin/python" scripts/gen_engine_reference.py \
      "<Qt dir>" core/src/test/resources/engine_reference.json
"""

import json
import random
import sys
import time


def main(qt_dir, out_path):
    sys.path.insert(0, qt_dir)
    import bitbully as bb
    from cfs_core import game as gm
    from cfs_core.engine import ITER_DEPTHS, Engine

    eng = Engine()
    assert eng.is_book_loaded()
    rnd = random.Random(20261008)

    def rand_seq(n, allow_end=False):
        while True:
            b = bb.Board()
            seq = []
            ok = True
            for _ in range(n):
                legal = list(b.legal_moves())
                if not legal:
                    ok = False
                    break
                c = rnd.choice(legal)
                b2 = b.play_on_copy(c)
                if b2.is_game_over() and not allow_end:
                    ok = False
                    break
                b = b2
                seq.append(c)
                if b.is_game_over():
                    break
            if ok:
                return seq

    def scores_list(d):
        return [d.get(c) for c in range(7)]

    # ---- iterative evaluation (the core of every engine move / analysis)
    fixed = [[], [3], [0, 1, 0, 1, 0, 1], [0, 1, 0, 1, 0], [3, 3, 2, 4],
             [3, 3, 3, 3, 3, 3], gm.parse_4gp("4433221")]
    plies = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 13, 14, 15, 16, 17, 18,
             19, 20, 22, 24, 26, 28, 30, 33, 36]
    positions = fixed + [rand_seq(n) for n in plies]
    iterative = []
    t_all = time.time()
    for seq in positions:
        b = gm.board_from_moves(seq)
        if b.is_game_over():
            continue
        stages = []

        def prog(depth, scores, nodes, dt):
            stages.append({"depth": depth, "scores": scores_list(scores), "nodes": nodes})

        t0 = time.time()
        scores, nodes = eng.iterative_scores(b, on_progress=None)
        dt = time.time() - t0
        # Re-run stage by stage to record every depth (same calls as iterative_scores).
        with eng.lock:
            eng.agent.reset_node_counter()
            eng.agent.reset_transposition_table()
            for depth in ITER_DEPTHS:
                part = eng.agent.score_all_moves(b, max_depth=depth)
                stages.append({"depth": depth, "scores": scores_list(dict(part)),
                               "nodes": eng.agent.get_node_counter()})
        assert stages[-1]["scores"] == scores_list(scores), seq
        assert stages[-1]["nodes"] == nodes, seq
        mtdf = eng.mtdf(b)
        ml = {str(s): bb.BitBully.score_to_moves_left(s, b)
              for s in set(v for v in scores.values())}
        iterative.append({"moves": seq, "stages": stages, "mtdf": mtdf,
                          "moves_left": ml, "seconds": round(dt, 3)})
        print(f"{len(seq):2d} plies  {dt:6.3f} s  nodes {nodes}", file=sys.stderr)
    print(f"iterative total {time.time() - t_all:.1f} s", file=sys.stderr)

    # ---- opening book: 12-stone positions (negamax returns the book value)
    book = []
    agent = eng.agent
    for i in range(300):
        seq = rand_seq(12)
        if len(seq) != 12:
            continue
        b = gm.board_from_moves(seq)
        book.append({"moves": seq, "value": agent.negamax(b)})
    # The documented examples of bitbully-databases (raw distance values).
    import bitbully_databases as bbd
    db = bbd.BitBullyDatabases("12-ply-dist")
    examples = []
    for rows, val in (
        ([[0, 0, 0, 0, 0, 0, 0], [0, 0, 0, 1, 0, 0, 0], [0, 1, 0, 2, 0, 0, 0],
          [0, 2, 0, 1, 0, 2, 0], [0, 1, 0, 2, 0, 1, 0], [0, 2, 0, 1, 0, 2, 0]], 71),
        ([[0, 0, 0, 0, 0, 0, 0], [0, 0, 0, 0, 0, 0, 0], [0, 0, 1, 1, 0, 0, 0],
          [0, 0, 2, 2, 0, 0, 0], [0, 1, 2, 1, 0, 0, 0], [0, 1, 1, 2, 0, 2, 2]], -88),
        ([[0, 0, 0, 0, 0, 0, 0], [0, 0, 0, 0, 0, 0, 0], [0, 1, 0, 0, 0, 0, 0],
          [0, 2, 2, 0, 1, 0, 0], [0, 2, 1, 0, 2, 0, 0], [1, 1, 2, 1, 2, 0, 0]], 73)):
        assert db.get_book_value(rows) == val
        examples.append({"rows": rows, "value": val})

    # ---- games: win detection, win cells, legal prefix
    games = []
    for i in range(80):
        seq = rand_seq(42, allow_end=True)
        g = gm.Game()
        g.set_moves(seq)
        games.append({"moves": seq, "over": g.is_game_over(), "winner": g.winner(),
                      "win_cells": sorted([list(c) for c in g.win_cells()]),
                      "last_move": list(g.last_move) if g.last_move else None})
    prefixes = []
    for i in range(80):
        raw = [rnd.randrange(-1, 9) for _ in range(rnd.randrange(0, 60))]
        prefixes.append({"raw": raw, "prefix": gm.legal_prefix(raw)})

    out = {"source": "cfs_core (Qt version) + bitbully 0.0.79 + bitbully-databases 0.0.2",
           "iter_depths": list(ITER_DEPTHS), "iterative": iterative, "book": book,
           "book_examples": examples, "games": games, "prefixes": prefixes}
    with open(out_path, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {out_path}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
