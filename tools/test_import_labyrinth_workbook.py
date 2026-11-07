"""Deterministic catalog import tests; no workbook, game client, or network is needed."""

import json
import sqlite3
import tempfile
import unittest
from collections import Counter
from contextlib import closing
from pathlib import Path

from import_labyrinth_workbook import relics_from_workbook, summon_units_from_database

PACK = Path(__file__).resolve().parents[1] / "android/app/src/main/assets/resource-packs/cn-bilibili"


class LabyrinthCatalogImportTest(unittest.TestCase):
    def test_named_columns_import_only_sale_rows_in_id_order(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as directory:
            source = Path(directory) / "catalog.db"
            with closing(sqlite3.connect(source)) as db, db:
                # Hash columns and physical order intentionally differ from the production DB.
                db.execute("CREATE TABLE labyrinth_summon_unit (irrelevant TEXT, description TEXT, "
                           "price INTEGER, item_id INTEGER PRIMARY KEY, summon_num INTEGER, name TEXT, summon_type INTEGER)")
                prices = [1200] * 13 + [1800] + [2800] * 8 + [3600]
                for index, price in reversed(list(enumerate(prices, start=1))):
                    db.execute("INSERT INTO labyrinth_summon_unit VALUES (?, ?, ?, ?, ?, ?, ?)",
                               ("not a price", f"description-{index}", price, index, 1, f"name-{index}", 1 if price == 2800 else 3))
                for index in range(10):
                    db.execute("INSERT INTO labyrinth_summon_unit VALUES ('hash', 'reward', 99999999, ?, 1, 'reward', 3)", (100 + index,))
                db.execute("INSERT INTO labyrinth_summon_unit VALUES ('hash', 'zero', 0, 200, 1, 'zero', 3)")
                db.execute("INSERT INTO labyrinth_summon_unit VALUES ('hash', 'negative', -1, 201, 1, 'negative', 3)")
            before = source.read_bytes()
            first = summon_units_from_database(source)
            second = summon_units_from_database(source)
            self.assertEqual(first, second)
            self.assertEqual(before, source.read_bytes())
            self.assertEqual(1, first["schemaVersion"])
            units = first["summonUnits"]
            self.assertEqual(23, len(units))
            self.assertEqual(list(map(str, range(1, 24))), [unit["id"] for unit in units])
            self.assertEqual({1200: 13, 1800: 1, 2800: 8, 3600: 1}, dict(Counter(unit["price"] for unit in units)))
            self.assertEqual("description-1", units[0]["description"])
            self.assertTrue(all(set(unit) == {"id", "name", "summonType", "summonNum", "price", "description"} for unit in units))

    def test_workbook_reimport_preserves_official_name_and_non_shop_zero(self):
        document = {"tables": [{"name": "迷宫遗物", "rows": [
            {"row": 2, "cells": [{"address": "A2", "value": "50005"}, {"address": "C2", "value": "迷宫大师"},
                                    {"address": "H2", "value": "α强化4金节点后"}]},
            {"row": 3, "cells": [{"address": "A3", "value": "11001"}, {"address": "C3", "value": "sale"},
                                    {"address": "H3", "value": "1800"}]},
        ]}]}
        initial, sale = relics_from_workbook(document)["relics"]
        self.assertEqual("拉比林斯之主", initial["name"])
        self.assertEqual(0, initial["price"])
        self.assertEqual("α强化4金节点后", initial["unlockCondition"])
        self.assertEqual(1800, sale["price"])
        self.assertEqual([0, 0], [initial["baseScore"], sale["baseScore"]])

    def test_bundled_catalog_and_relic_contract(self):
        manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
        self.assertIn("labyrinth-summon-units.json", manifest["files"])
        units = json.loads((PACK / "labyrinth-summon-units.json").read_text(encoding="utf-8"))["summonUnits"]
        self.assertEqual(23, len(units))
        self.assertEqual({1200: 13, 1800: 1, 2800: 8, 3600: 1}, dict(Counter(unit["price"] for unit in units)))
        self.assertTrue(all(0 < unit["price"] < 99999999 and unit["summonNum"] == 1 for unit in units))
        self.assertEqual(23, len({unit["id"] for unit in units}))
        relics = json.loads((PACK / "relics.laby-workbook.json").read_text(encoding="utf-8"))["relics"]
        self.assertEqual(109, len(relics))
        self.assertEqual(28, sum(relic["price"] == 0 for relic in relics))
        self.assertEqual({1800: 25, 2800: 25, 3800: 26, 9999: 5}, dict(Counter(relic["price"] for relic in relics if relic["price"] > 0)))
        self.assertTrue(all(relic["baseScore"] == 0 for relic in relics))
        self.assertEqual("拉比林斯之主", next(relic["name"] for relic in relics if relic["id"] == "50005"))


if __name__ == "__main__":
    unittest.main()
