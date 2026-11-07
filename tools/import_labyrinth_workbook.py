#!/usr/bin/env python3
"""Import structured tables from the agreed labyrinth workbook.

The workbook is produced by Tencent Office and contains many in-cell images.
This importer reads XLSX XML directly instead of relying on its style table.
Image-heavy guide sheets are intentionally excluded from the application data.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import xml.etree.ElementTree as ET
from contextlib import closing
from pathlib import Path
from zipfile import ZipFile

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
q = lambda name: f"{{{MAIN_NS}}}{name}"

INCLUDED_SHEETS = (
    "迷宫遗物",
    "事件",
    "迷宫遗物印记",
    "环境效果",
    "功能性角色",
    "任务",
    "累计奖励",
    "α强化",
    "其它数据",
    "后续更新",
)
EXCLUDED_SHEETS = ("EXTREME", "BOSS", "攻略", "EX首饰")
# Mainland-CN display names override workbook translations without editing the workbook.
RELIC_NAME_OVERRIDES = {"50005": "拉比林斯之主"}
NON_SHOP_SUMMON_PRICE = 99_999_999


def column_number(address: str) -> int:
    letters = "".join(char for char in address if char.isalpha())
    value = 0
    for char in letters:
        value = value * 26 + ord(char.upper()) - ord("A") + 1
    return value


def shared_strings(archive: ZipFile) -> list[str]:
    root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    return ["".join(text.text or "" for text in item.iter(q("t"))) for item in root.findall(q("si"))]


def sheet_targets(archive: ZipFile) -> dict[str, str]:
    relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    targets = {
        item.attrib["Id"]: item.attrib["Target"]
        for item in relationships.findall(f"{{{PACKAGE_REL_NS}}}Relationship")
    }
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    result = {}
    for sheet in workbook.find(q("sheets")):
        relationship_id = sheet.attrib[f"{{{REL_NS}}}id"]
        target = targets[relationship_id]
        result[sheet.attrib["name"]] = target if target.startswith("xl/") else f"xl/{target.lstrip('/')}"
    return result


def cell_value(cell: ET.Element, strings: list[str]) -> tuple[str, str | None] | None:
    cell_type = cell.attrib.get("t", "n")
    value_node = cell.find(q("v"))
    raw_value = value_node.text if value_node is not None and value_node.text is not None else ""
    if cell_type == "e":
        # In-cell images appear as #VALUE! cells and are deliberately omitted.
        return None
    if cell_type == "s" and raw_value:
        value = strings[int(raw_value)]
    elif cell_type == "inlineStr":
        value = "".join(text.text or "" for text in cell.iter(q("t")))
    elif cell_type == "b":
        value = "TRUE" if raw_value == "1" else "FALSE"
    else:
        value = raw_value
    formula_node = cell.find(q("f"))
    formula = f"={formula_node.text}" if formula_node is not None and formula_node.text else None
    if not value and formula is None:
        return None
    return value.replace("\r\n", "\n"), formula


def read_table(archive: ZipFile, path: str, strings: list[str]) -> list[dict[str, object]]:
    root = ET.fromstring(archive.read(path))
    rows: list[dict[str, object]] = []
    sheet_data = root.find(q("sheetData"))
    if sheet_data is None:
        return rows
    for row in sheet_data.findall(q("row")):
        cells: list[dict[str, str]] = []
        for cell in row.findall(q("c")):
            parsed = cell_value(cell, strings)
            if parsed is None:
                continue
            value, formula = parsed
            item = {"address": cell.attrib.get("r", ""), "value": value}
            if formula is not None:
                item["formula"] = formula
            cells.append(item)
        if cells:
            cells.sort(key=lambda item: column_number(item["address"]))
            rows.append({"row": int(row.attrib["r"]), "cells": cells})
    return rows


def build_workbook_document(source: Path) -> dict[str, object]:
    with ZipFile(source) as archive:
        strings = shared_strings(archive)
        targets = sheet_targets(archive)
        tables = [
            {"name": name, "rows": read_table(archive, targets[name], strings)}
            for name in INCLUDED_SHEETS
            if name in targets
        ]
    return {
        "schemaVersion": 1,
        "source": {
            "file": str(source).replace("\\", "/"),
            "note": "仅导入可结构化工作表；EXTREME、BOSS、攻略和 EX 首饰图片表不进入应用。",
        },
        "excludedSheets": list(EXCLUDED_SHEETS),
        "tables": tables,
    }


def relics_from_workbook(document: dict[str, object]) -> dict[str, object]:
    table = next(table for table in document["tables"] if table["name"] == "迷宫遗物")
    records: list[dict[str, object]] = []
    for row in table["rows"]:
        cells = {column_number(cell["address"]): cell["value"] for cell in row["cells"]}
        if row["row"] == 1 or not cells.get(1) or not cells.get(3):
            continue
        price_text = str(cells.get(8, ""))
        price_match = re.fullmatch(r"\d+", price_text)
        mark = str(cells.get(6, ""))
        records.append(
            {
                "id": str(cells[1]),
                "name": RELIC_NAME_OVERRIDES.get(str(cells[1]), str(cells[3])),
                "tags": [mark] if mark else [],
                "rarity": str(cells.get(4, "")),
                "effect": str(cells.get(5, "")),
                "mark": mark,
                "markBonus": str(cells.get(7, "")),
                "unlockCondition": "" if price_match else price_text,
                "baseScore": 0,
                "price": int(price_text) if price_match else 0,
                "notes": "来自共识迷宫数据表；评分保持中性占位。",
                "references": {},
            }
        )
    return {"schemaVersion": 1, "relics": records}


def summon_units_from_database(source: Path) -> dict[str, object]:
    """Import sale prices by named columns; unrelated hashed columns are never consumed."""
    records: list[dict[str, object]] = []
    uri = source.resolve().as_uri() + "?mode=ro&immutable=1"
    with closing(sqlite3.connect(uri, uri=True)) as database:
        rows = database.execute(
            "SELECT item_id, name, summon_type, summon_num, price, description "
            "FROM labyrinth_summon_unit WHERE price > 0 AND price < ? ORDER BY item_id",
            (NON_SHOP_SUMMON_PRICE,),
        )
        for item_id, name, summon_type, summon_num, price, description in rows:
            if item_id <= 0 or not name.strip() or summon_type not in (1, 3) or summon_num != 1:
                raise ValueError(f"Invalid shop summon item: {item_id}")
            records.append({
                "id": str(item_id), "name": name, "summonType": summon_type,
                "summonNum": summon_num, "price": price, "description": description,
            })
    if not records:
        raise ValueError("No shop-listed summon items found")
    return {"schemaVersion": 1, "summonUnits": records}


def write_json(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, nargs="?")
    parser.add_argument("workbook_output", type=Path, nargs="?")
    parser.add_argument("relics_output", type=Path, nargs="?")
    parser.add_argument("--summon-database", type=Path, help="Read-only mainland-CN master database")
    parser.add_argument("--summon-output", type=Path, help="Shop-listed summon resource JSON")
    args = parser.parse_args()
    workbook_args = (args.source, args.workbook_output, args.relics_output)
    if any(workbook_args) and not all(workbook_args):
        parser.error("source, workbook_output and relics_output must be supplied together")
    if bool(args.summon_database) != bool(args.summon_output):
        parser.error("--summon-database and --summon-output must be supplied together")
    if not args.source and not args.summon_database:
        parser.error("provide a workbook or a summon database")
    if args.source:
        document = build_workbook_document(args.source)
        relics = relics_from_workbook(document)
        write_json(args.workbook_output, document)
        write_json(args.relics_output, relics)
        print(f"wrote {len(document['tables'])} tables and {len(relics['relics'])} relics")
    if args.summon_database:
        summons = summon_units_from_database(args.summon_database)
        write_json(args.summon_output, summons)
        print(f"wrote {len(summons['summonUnits'])} shop summon items")


if __name__ == "__main__":
    main()
