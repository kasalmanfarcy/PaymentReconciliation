#!/usr/bin/env python3
"""Deterministic synthetic datasets for ReconFlow benchmarks and load tests (M9.5).

Writes a matched pair of CSVs in the exact contracts of docs/requirements.md §7.2/§7.3 (ledger and
gateway), plus expected.json: the counts and totals a correct import and reconciliation of that
pair must produce. Every benchmark run is then also a correctness check.

Reproducible: the same arguments always give byte-identical files. All randomness comes from one
random.Random(seed) (never the global generator), amounts are Decimal (never float: 0.1 + 0.2 is
0.30000000000000004), and nothing reads the clock. Python 3 standard library only.

Discrepancy categories are disjoint, so expected.json is exact:

  matched             both files, same amount                       -> one EXACT match
  missing_in_gateway  ledger only                                    -> MISSING_IN_GATEWAY (unmatched left)
  missing_in_ledger   gateway only                                   -> MISSING_IN_LEDGER (unmatched right)
  amount_mismatch     both files, gateway = ledger + 1.00            -> AMOUNT_MISMATCH
  duplicate           ledger once, gateway the same row twice        -> one DUPLICATE_IN_SOURCE case (D1 + O1)
  malformed           an extra row with a bad date, currency or amount -> a row error, never a record

Examples:

  # the 50k benchmark pair (docs/benchmarks.md)
  python3 scripts/generate_dataset.py --preset 50k --out target/datasets/50k
  # stress: 500k rows, within the 500,000-row and 50 MB upload limits
  python3 scripts/generate_dataset.py --preset 500k --out target/datasets/500k
  # background data for load tests: several organizations, skewed sizes, one pair per month
  python3 scripts/generate_dataset.py --preset preload --out target/datasets/preload
"""

import argparse
import csv
import io
import json
import random
import sys
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

PRESETS = {
    "50k": dict(rows=50_000, days=30),
    "500k": dict(rows=500_000, days=30),
    # 6 months for 10 organizations with a long-tailed size mix (one large, a few medium, many
    # small), as docs/scalability.md §1 assumes, scaled to what a laptop imports in minutes.
    "preload": dict(rows=60_000, orgs=10, months=6, start="2026-05-01"),
}

LEDGER_HEADER = ["reference", "amount", "currency", "status", "transaction_at", "transaction_type", "order_id"]
GATEWAY_HEADER = ["merchant_reference", "amount", "currency", "status", "transaction_at", "gateway_transaction_id"]
LEDGER_OFFSET = timezone(timedelta(hours=4))  # the ledger writes local (+04:00) time; rule N7 converts to UTC
CENT = Decimal("0.01")


def parse_args(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--preset", choices=sorted(PRESETS), help="named sizes; explicit flags override them")
    p.add_argument("--rows", type=int, help="references per file pair (per organization and month)")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--currency", default="AED")
    p.add_argument("--start", help="first day (YYYY-MM-DD) of the period; default 2026-10-01")
    p.add_argument("--days", type=int, help="days in the period (ignored with --months)")
    p.add_argument("--months", type=int, default=0, help="one file pair per calendar month from --start")
    p.add_argument("--orgs", type=int, default=1, help="organizations, each with its own files (skewed sizes)")
    p.add_argument("--mismatch-rate", type=float, default=0.01)
    p.add_argument("--missing-in-gateway", type=float, default=0.005)
    p.add_argument("--missing-in-ledger", type=float, default=0.005)
    p.add_argument("--duplicate-rate", type=float, default=0.002)
    p.add_argument("--malformed-rate", type=float, default=0.001)
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args(argv)
    preset = PRESETS.get(args.preset, {})
    args.rows = args.rows or preset.get("rows") or 50_000
    args.days = args.days or preset.get("days") or 30
    args.orgs = args.orgs if args.orgs != 1 else preset.get("orgs", 1)
    args.months = args.months or preset.get("months", 0)
    args.start = date.fromisoformat(args.start or preset.get("start") or "2026-10-01")
    return args


def org_rows(total, orgs):
    """Long-tailed sizes: organization k gets a share proportional to 1/k (k = 1..orgs)."""
    if orgs == 1:
        return [total]
    weights = [1 / k for k in range(1, orgs + 1)]
    scale = total / weights[0]  # the largest organization gets `total` rows
    return [max(100, int(scale * w)) for w in weights]


def periods(args):
    """(label, first day, number of days) for each file pair of one organization."""
    if not args.months:
        return [("", args.start, args.days)]
    out, first = [], args.start.replace(day=1)
    for _ in range(args.months):
        nxt = (first.replace(day=28) + timedelta(days=4)).replace(day=1)
        out.append((first.strftime("%Y-%m"), first, (nxt - first).days))
        first = nxt
    return out


def generate_pair(rng, rows, first_day, days, args, prefix):
    cuts = []
    acc = 0.0
    for name, rate in (
        ("missing_in_gateway", args.missing_in_gateway),
        ("missing_in_ledger", args.missing_in_ledger),
        ("amount_mismatch", args.mismatch_rate),
        ("duplicate", args.duplicate_rate),
    ):
        acc += rate
        cuts.append((acc, name))
    if acc >= 1:
        sys.exit("discrepancy rates add up to 1 or more")

    ledger, gateway = [], []
    counts = dict(matched=0, missing_in_gateway=0, missing_in_ledger=0, amount_mismatch=0, duplicate=0)
    sums = dict(matched=Decimal(0), missing_in_gateway=Decimal(0), missing_in_ledger=Decimal(0),
                mismatch_net=Decimal(0))
    malformed = dict(ledger=0, gateway=0)

    for i in range(rows):
        ref = f"{prefix}{i:08d}"
        day = first_day + timedelta(days=rng.randrange(days))
        instant = datetime(day.year, day.month, day.day, tzinfo=timezone.utc) + timedelta(seconds=rng.randrange(86_400))
        # The gateway sees the payment up to two minutes later, never past midnight UTC.
        lag = min(rng.randrange(120), 86_399 - (instant.hour * 3600 + instant.minute * 60 + instant.second))
        gw_instant = instant + timedelta(seconds=lag)
        amount = (Decimal(rng.randrange(100, 5_000_000)) / 100).quantize(CENT)

        def ledger_row(amt):
            return [ref, f"{amt}", args.currency, "SUCCESS",
                    instant.astimezone(LEDGER_OFFSET).isoformat(), "PAYMENT", f"ORD-{rng.randrange(10**9):09d}"]

        def gateway_row(amt, suffix=""):
            return [ref, f"{amt}", args.currency.lower(), "captured",
                    gw_instant.isoformat().replace("+00:00", "Z"), f"gw_{prefix}{i:08d}{suffix}"]

        r = rng.random()
        kind = next((name for cut, name in cuts if r < cut), "matched")
        counts[kind] += 1
        if kind == "missing_in_gateway":
            ledger.append(ledger_row(amount))
            sums["missing_in_gateway"] += amount
        elif kind == "missing_in_ledger":
            gateway.append(gateway_row(amount))
            sums["missing_in_ledger"] += amount
        elif kind == "amount_mismatch":
            ledger.append(ledger_row(amount))
            gateway.append(gateway_row(amount + Decimal("1.00")))
            sums["mismatch_net"] += Decimal("1.00")  # gateway - ledger
        elif kind == "duplicate":
            ledger.append(ledger_row(amount))
            row = gateway_row(amount)
            gateway += [row, list(row)]  # an export glitch: the very same row twice
        else:
            ledger.append(ledger_row(amount))
            gateway.append(gateway_row(amount))
            sums["matched"] += amount

    # Malformed rows: extra references, so they never change the counts above.
    n_bad = round(rows * args.malformed_rate)
    for j in range(n_bad):
        side = "ledger" if rng.random() < 0.5 else "gateway"
        flaw = rng.randrange(3)
        day = first_day + timedelta(days=rng.randrange(days))
        ts = datetime(day.year, day.month, day.day, 12, tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")
        amount, currency = "10.00", args.currency
        if flaw == 0:
            ts = day.strftime("%d/%m/%Y")  # INVALID_TIMESTAMP
        elif flaw == 1:
            currency = "XYZ"  # INVALID_CURRENCY
        else:
            amount = ""  # MISSING_REQUIRED_FIELD
        ref = f"BAD-{prefix}{j:06d}"
        if side == "ledger":
            ledger.append([ref, amount, currency, "SUCCESS", ts, "PAYMENT", ""])
        else:
            gateway.append([ref, amount, currency.lower(), "captured", ts, f"gw_bad_{j}"])
        malformed[side] += 1

    rng.shuffle(ledger)  # real files aren't sorted by reference
    rng.shuffle(gateway)

    period_end = first_day + timedelta(days=days - 1)
    expected = {
        "periodStart": first_day.isoformat(),
        "periodEnd": period_end.isoformat(),
        "currency": args.currency,
        "ledger": {"totalRows": len(ledger), "validRows": len(ledger) - malformed["ledger"],
                   "errorRows": malformed["ledger"]},
        "gateway": {"totalRows": len(gateway), "validRows": len(gateway) - malformed["gateway"],
                    "errorRows": malformed["gateway"]},
        # Field names as in GET /api/v1/reconciliation-runs/{id} (api-design.md §8.10); the ledger is
        # the left source, so missing-in-gateway is "unmatched left".
        "run": {
            "matchedCount": counts["matched"],
            "matchedAmount": f"{sums['matched']:.4f}",
            "mismatchCount": counts["amount_mismatch"],
            "mismatchNetDifference": f"{sums['mismatch_net']:.4f}",
            "unmatchedLeftCount": counts["missing_in_gateway"],
            "unmatchedLeftAmount": f"{sums['missing_in_gateway']:.4f}",
            "unmatchedRightCount": counts["missing_in_ledger"],
            "unmatchedRightAmount": f"{sums['missing_in_ledger']:.4f}",
            "duplicateCount": counts["duplicate"],
            "currencyMismatchCount": 0,
            "excludedCount": 0,
        },
    }
    return ledger, gateway, expected


def write_csv(path, header, rows):
    buf = io.StringIO(newline="")
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    path.write_bytes(buf.getvalue().encode("utf-8"))


def main(argv):
    args = parse_args(argv)
    sizes = org_rows(args.rows, args.orgs)
    manifest = []
    for k, rows in enumerate(sizes, start=1):
        for label, first_day, days in periods(args):
            # One generator per pair, seeded from (seed, org, month): adding an org or a month never
            # changes the files of the others.
            rng = random.Random(f"{args.seed}/{k}/{label}")
            prefix = "PAY-" if args.orgs == 1 else f"O{k:02d}-"
            if label:
                prefix += label.replace("-", "") + "-"
            ledger, gateway, expected = generate_pair(rng, rows, first_day, days, args, prefix)
            out = args.out
            if args.orgs > 1:
                out = out / f"org-{k:02d}"
            if label:
                out = out / label
            out.mkdir(parents=True, exist_ok=True)
            write_csv(out / "ledger.csv", LEDGER_HEADER, ledger)
            write_csv(out / "gateway.csv", GATEWAY_HEADER, gateway)
            (out / "expected.json").write_text(json.dumps(expected, indent=2) + "\n")
            manifest.append({"dir": str(out.relative_to(args.out)) or ".", "org": k, "rows": rows,
                             "periodStart": expected["periodStart"], "periodEnd": expected["periodEnd"]})
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {len(manifest)} file pair(s) under {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
