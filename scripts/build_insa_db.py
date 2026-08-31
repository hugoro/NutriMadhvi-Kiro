#!/usr/bin/env python3
"""
Build assets/nutrition/insa_pt.db from INSA TCA Excel.

Downloads the latest INSA Tabela da Composição de Alimentos (v7.1 2026)
from portfir.insa.min-saude.pt and converts it to a SQLite database
suitable for embedding in the Android app as an asset.

Usage:
    python3 scripts/build_insa_db.py

Output:
    app/src/main/assets/nutrition/insa_pt.db
"""

from __future__ import annotations
import os, sys, re, sqlite3, unicodedata, urllib.request
from pathlib import Path

INSA_URL = "https://portfir.insa.min-saude.pt/wp-content/uploads/2025/11/insa_tca.xlsx"
SCRIPT_DIR = Path(__file__).parent
PROJECT_ROOT = SCRIPT_DIR.parent
XLSX_CACHE = Path("/tmp/insa_tca.xlsx")
OUT_DB = PROJECT_ROOT / "app/src/main/assets/nutrition/insa_pt.db"

# Column indices in the INSA Excel (row 1 = header, data starts at row 2)
COL_COD      = 0
COL_NAME     = 1
COL_GROUP    = 2
COL_KCAL     = 5
COL_FAT      = 7
COL_SAT_FAT  = 8
COL_CARBS    = 13
COL_SUGARS   = 14
COL_PROTEIN  = 19


def normalize(text: str) -> str:
    """Lowercase, strip accents, collapse whitespace."""
    if not text:
        return ""
    nfd = unicodedata.normalize("NFD", text.lower())
    stripped = "".join(c for c in nfd if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", stripped).strip()


def to_float(val) -> float | None:
    if val is None:
        return None
    s = str(val).strip().replace(",", ".")
    if s in ("", "nd", "ND", "-", "tr", "Tr", "n.d.", "N.D."):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def download_xlsx() -> Path:
    if XLSX_CACHE.exists():
        print(f"  Using cached {XLSX_CACHE}")
        return XLSX_CACHE
    print(f"  Downloading INSA TCA from {INSA_URL}...")
    urllib.request.urlretrieve(INSA_URL, XLSX_CACHE)
    print(f"  Downloaded {XLSX_CACHE.stat().st_size // 1024}KB")
    return XLSX_CACHE


def build_db(xlsx_path: Path, out_path: Path) -> int:
    try:
        import openpyxl
    except ImportError:
        sys.exit("Install openpyxl: pip3 install openpyxl")

    print(f"  Reading {xlsx_path}...")
    wb = openpyxl.load_workbook(xlsx_path, read_only=True, data_only=True)
    ws = wb["INSA - BDCA_v 7.1 - 2026"]

    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        out_path.unlink()

    con = sqlite3.connect(out_path)
    cur = con.cursor()
    cur.executescript("""
        CREATE TABLE foods (
            id              INTEGER PRIMARY KEY,
            insa_code       TEXT NOT NULL,
            name_pt         TEXT NOT NULL,
            normalized_name TEXT NOT NULL,
            food_group      TEXT,
            per100g_kcal    REAL,
            per100g_fat     REAL,
            per100g_sat_fat REAL,
            per100g_carbs   REAL,
            per100g_sugars  REAL,
            per100g_protein REAL
        );
        CREATE INDEX idx_normalized ON foods(normalized_name);
    """)

    inserted = 0
    for row_idx, row in enumerate(ws.iter_rows(values_only=True)):
        if row_idx == 0:
            continue  # merged header row
        if row_idx == 1:
            continue  # column names row

        cod  = row[COL_COD]
        name = row[COL_NAME]
        if not cod or not name:
            continue

        name_str  = str(name).strip()
        group_str = str(row[COL_GROUP]).strip() if row[COL_GROUP] else None

        cur.execute("""
            INSERT INTO foods
                (insa_code, name_pt, normalized_name, food_group,
                 per100g_kcal, per100g_fat, per100g_sat_fat,
                 per100g_carbs, per100g_sugars, per100g_protein)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            str(cod).strip(),
            name_str,
            normalize(name_str),
            group_str,
            to_float(row[COL_KCAL]),
            to_float(row[COL_FAT]),
            to_float(row[COL_SAT_FAT]),
            to_float(row[COL_CARBS]),
            to_float(row[COL_SUGARS]),
            to_float(row[COL_PROTEIN]),
        ))
        inserted += 1

    con.commit()
    con.close()
    return inserted


def verify(out_path: Path) -> None:
    con = sqlite3.connect(out_path)
    cur = con.cursor()
    tests = [
        ("azeite",        "azeite"),
        ("bacalhau",      "bacalhau"),
        ("ovo",           "ovo"),
        ("pao",           "pao"),
    ]
    print("\n  Spot-checks:")
    for keyword, label in tests:
        rows = cur.execute(
            "SELECT name_pt, per100g_kcal, per100g_fat, per100g_protein "
            "FROM foods WHERE normalized_name LIKE ? LIMIT 2",
            (f"%{keyword}%",)
        ).fetchall()
        for r in rows:
            print(f"    [{label}] {r[0]}: {r[1]} kcal, fat={r[2]}g, prot={r[3]}g")
    con.close()


if __name__ == "__main__":
    print("Building INSA PT nutrition database...")
    xlsx = download_xlsx()
    n = build_db(xlsx, OUT_DB)
    size_kb = OUT_DB.stat().st_size // 1024
    print(f"  Inserted {n} foods → {OUT_DB} ({size_kb}KB)")
    verify(OUT_DB)
    print("Done.")
