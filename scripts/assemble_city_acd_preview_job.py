#!/usr/bin/env python3
"""组装 city_acd_wf_jq_preview.yaml。"""

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
JOBS = ROOT / "dg-web/src/main/resources/configs/jobs"
FRAGMENTS = JOBS / "_acd_generated.yaml"
TARGET = JOBS / "city_acd_wf_jq_preview.yaml"

PERSON = ["王伟", "李芳", "张娜", "刘秀英", "陈敏", "杨静", "赵强", "黄磊"]


def acd_human_table(name, table, count, source, ref_table):
    return {
        "name": name,
        "count": count,
        "writer": {"type": "csv", "connection": "traffic-output", "mode": "insert"},
        "depends_on": [source],
        "schema": {
            "table": table,
            "fields": [
                {"name": "sgbh", "type": "VARCHAR", "generator": {"strategy": "reference", "source": source, "field": "sgbh"}},
                {"name": "xzqh", "type": "VARCHAR", "generator": {"strategy": "reference", "source": source, "field": "xzqh"}},
                {"name": "rybh", "type": "VARCHAR", "generator": {"strategy": "sequence", "start": 1, "step": 1}},
                {"name": "xm", "type": "VARCHAR", "generator": {"strategy": "enum", "values": PERSON}},
                {"name": "xb", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["1", "2"]}},
                {"name": "sfzmhm", "type": "VARCHAR", "generator": {"strategy": "regex", "pattern": "440115[0-9]{4}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9X]"}},
                {"name": "dh", "type": "VARCHAR", "generator": {"strategy": "regex", "pattern": "1[3-9][0-9]{9}"}},
                {"name": "nl", "type": "VARCHAR", "generator": {"strategy": "random", "type": "int", "min": 18, "max": 70}},
                {"name": "hphm", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["", "粤A12345", "粤A67890", "粤B1D2E3", "粤C5F6G7", "粤AD12345", "粤AF67890"]}},
                {"name": "hpzl", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["01", "02", "07", "15", "16", "51"]}},
                {"name": "clpp", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["大众牌", "丰田牌", "本田牌", "别克牌", "比亚迪牌", "吉利牌", "雅迪牌", "爱玛牌", ""]}},
                {"name": "sgzr", "type": "VARCHAR", "generator": {"strategy": "random", "type": "int", "min": 1, "max": 5}},
                {"name": "jtfs", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["K3", "K31", "K33", "H1", "H2", "F1", "F2", "F6", "A1"]}},
                {"name": "sfdsr", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["1", "0"]}},
                {"name": "my_dt", "type": "VARCHAR", "generator": {"strategy": "enum", "values": ["2024-06-26 00:00:00.000"]}},
            ],
        },
        "constraints": [
            {"level": "field", "field": "sgbh", "type": "foreign_key", "ref_table": ref_table, "ref_field": "sgbh"}
        ],
    }


def make_acd_main(name, table, count, fragments, fields_key, mutate_key):
    return {
        "name": name,
        "count": count,
        "writer": {"type": "postgresql", "connection": "dev-safety", "mode": "insert"},
        "schema": {
            "table": table,
            "seed": {
                "reader": fragments["acd_road_seed_reader"],
                "mutate": fragments[mutate_key],
            },
            "fields": fragments[fields_key],
        },
    }


class LiteralDumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


def _represent_dict(dumper, data):
    if isinstance(data, dict) and "strategy" in data:
        return dumper.represent_mapping("tag:yaml.org,2002:map", data, flow_style=True)
    return dumper.represent_mapping("tag:yaml.org,2002:map", data, flow_style=False)


LiteralDumper.add_representer(dict, _represent_dict)


def _str_presenter(dumper, data):
    if "\n" in data:
        return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", data)


LiteralDumper.add_representer(str, _str_presenter)

with FRAGMENTS.open(encoding="utf-8") as handle:
    fragments = yaml.safe_load(handle)

with TARGET.open(encoding="utf-8") as handle:
    existing = yaml.safe_load(handle)

wf_jq_tables = [table for table in existing["tables"] if table["name"] in ("wf_3y", "jqxx")]

job = {
    "job": "city_acd_wf_jq_preview",
    "tables": [
        make_acd_main("acd_file", "acd_file_sh", 5, fragments, "acd_file_fields", "acd_file_mutate"),
        acd_human_table("acd_filehuman", "acd_filehuman", 10, "acd_file", "acd_file"),
        make_acd_main("acd_dutysimple", "acd_dutysimple_sh", 5, fragments, "acd_dutysimple_fields", "acd_dutysimple_mutate"),
        acd_human_table("acd_dutysimplehuman", "acd_dutysimplehuman", 10, "acd_dutysimple", "acd_dutysimple"),
        *wf_jq_tables,
    ],
}

with TARGET.open("w", encoding="utf-8") as handle:
    handle.write("# 参考 export_city_acd_wf_join_masked.py 生成规则\n")
    yaml.dump(job, handle, Dumper=LiteralDumper, allow_unicode=True, sort_keys=False, width=120)

print(f"assembled {TARGET}")
