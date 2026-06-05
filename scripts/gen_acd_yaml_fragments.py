#!/usr/bin/env python3
"""生成 acd_file / acd_dutysimple 共享 YAML 片段。"""

import yaml

PERSON = ["王伟", "李芳", "张娜", "刘秀英", "陈敏", "杨静", "赵强", "黄磊"]
EMPTY = {"strategy": "enum", "values": [""]}
SWRS = {"strategy": "enum", "values": [0, 0, 0, 0, 1]}
DT_RANGE = {
    "strategy": "random",
    "type": "datetime",
    "min": "2024-10-01 00:00:00",
    "max": "2024-12-31 23:59:59",
}
MY_DT = {
    "strategy": "random",
    "type": "datetime",
    "min": "2024-06-26 00:00:00",
    "max": "2024-06-26 00:00:00",
}
NAMES = {"strategy": "enum", "values": PERSON}

SEED_QUERY = """SELECT
  st_astext(tt.geom) geom,
  st_astext(tt.geom) geomwkt,
  tt.roadclid,
  tt.lm,
  tt.dllx,
  tt.xzqh,
  tt.sgdd,
  tt.fnode,
  tt.tnode,
  tt.road_type,
  police.pcsid as pcs_id,
  police.name as pcs_name
FROM (
  SELECT
    ST_LineInterpolatePoint((ST_Dump(geom)).geom, random()) AS geom,
    roadclid,
    name AS lm,
    CASE
      WHEN roadtype = '4' THEN '10'
      WHEN roadtype = '5' THEN '21'
      ELSE '0'
    END AS dllx,
    distric AS xzqh,
    froadname || '往' || direction || 'xx米' AS sgdd,
    fnode,
    tnode,
    roadtype AS road_type
  FROM s_xc_roadcl_info
  WHERE roadclid::numeric > 0
  UNION ALL
  SELECT
    geom,
    intersectionid AS roadclid,
    hengxiangroadname AS lm,
    '0' AS dllx,
    xzqh,
    hengxiangroadname || '与' || zongxiangroadname || '路口' AS sgdd,
    '' AS fnode,
    '' AS tnode,
    '0' AS road_type
  FROM fsc_t_intersection_info
  WHERE intersectionid::numeric > 0
) tt
LEFT JOIN LATERAL (
  SELECT pcsid, name
  FROM bas_police_station
  WHERE ST_Contains(geom, tt.geom)
  LIMIT 1
) police ON true
"""


def fld(name, typ, gen):
    return {"name": name, "type": typ, "generator": gen}


def empty(name, typ="VARCHAR"):
    return fld(name, typ, EMPTY)


seed_fields = [
    fld("lm", "VARCHAR", {"strategy": "seed"}),
    fld("xzqh", "VARCHAR", {"strategy": "seed"}),
    fld("sgdd", "VARCHAR", {"strategy": "seed"}),
    fld("roadclid", "VARCHAR", {"strategy": "seed"}),
    fld("geom", "GEOMETRY", {"strategy": "seed"}),
    fld("geomwkt", "VARCHAR", {"strategy": "seed"}),
    fld("pcs_id", "VARCHAR", {"strategy": "seed"}),
    fld("pcs_name", "VARCHAR", {"strategy": "seed"}),
    fld("dllx", "VARCHAR", {"strategy": "seed"}),
    fld("fnode", "VARCHAR", {"strategy": "seed"}),
    fld("tnode", "VARCHAR", {"strategy": "seed"}),
    fld("road_type", "VARCHAR", {"strategy": "seed"}),
]

mutate_core = [
    fld("sgbh", "VARCHAR", {"strategy": "regex", "pattern": "30000[0-9]{16}"}),
    fld("sgfssj", "TIMESTAMP", DT_RANGE),
    fld("kskcsj", "TIMESTAMP", DT_RANGE),
    fld("jskcsj", "TIMESTAMP", DT_RANGE),
    fld("djbh", "VARCHAR", {"strategy": "regex", "pattern": "4401152024[0-9]{6}"}),
    fld("xq", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 7}),
    fld("lh", "VARCHAR", {"strategy": "regex", "pattern": "[0-9]{7}"}),
    fld("gls", "INTEGER", {"strategy": "random", "type": "int", "min": 0, "max": 500}),
    fld("ms", "INTEGER", {"strategy": "random", "type": "int", "min": 0, "max": 999}),
    fld("qdms", "VARCHAR", {"strategy": "enum", "values": ["0"]}),
    fld("jdwz", "INTEGER", {"strategy": "random", "type": "int", "min": 0, "max": 500999}),
    fld("szrs", "INTEGER", {"strategy": "random", "type": "int", "min": 0, "max": 3}),
    fld("swrs", "INTEGER", SWRS),
    fld("swrsq", "INTEGER", SWRS),
    fld("swrs7", "INTEGER", SWRS),
    fld("swrs30", "INTEGER", SWRS),
    fld("swrs7_1", "INTEGER", SWRS),
    fld("swrs_end", "INTEGER", SWRS),
    fld("zsrs", "INTEGER", {"strategy": "enum", "values": [0]}),
    fld("qsrs", "INTEGER", {"strategy": "enum", "values": [0]}),
    fld("ssrs", "INTEGER", {"strategy": "random", "type": "int", "min": 0, "max": 3}),
    fld("ldcsl", "VARCHAR", {"strategy": "random", "type": "int", "min": 0, "max": 5}),
    fld("fjdcsl", "VARCHAR", {"strategy": "random", "type": "int", "min": 0, "max": 5}),
    fld("xrsl", "VARCHAR", {"strategy": "random", "type": "int", "min": 0, "max": 5}),
    fld("zjccss", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 5}),
    fld("sglx", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 13}),
    fld("rdyyfl", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 50}),
    fld("sgrdyy", "INTEGER", {"strategy": "random", "type": "int", "min": 1000, "max": 1400}),
    fld("tq", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 4}),
    fld("sgxt", "VARCHAR", {"strategy": "enum", "values": ["11", "21", "31", "32"]}),
    fld("sfty", "VARCHAR", {"strategy": "enum", "values": ["", "1"]}),
    fld("cljsg", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 5}),
    fld("pzfs", "INTEGER", {"strategy": "random", "type": "int", "min": 1, "max": 2}),
    fld("glbm", "VARCHAR", {"strategy": "enum", "values": ["xq440115"]}),
    fld("glbm_name", "VARCHAR", {"strategy": "enum", "values": ["番禺大队"]}),
    fld("zonename", "VARCHAR", {"strategy": "enum", "values": ["番禺大队"]}),
    fld("my_dt", "TIMESTAMP", MY_DT),
    fld("jd", "DOUBLE", {"strategy": "enum", "values": [0]}),
    fld("wd", "DOUBLE", {"strategy": "enum", "values": [0]}),
]

empty_names = [
    "zhdmwz", "zyglss", "dlaqsx", "jtxhfs", "fhsslx", "dlwlgl", "lmzk", "lbqk", "lmjg",
    "lkldlx", "dlxx", "jyaq", "njd", "dx", "zmtj", "yzwxp", "glxzdj", "dzzb", "msg",
    "all_jtfs_type", "rdlv", "node_id", "tysgzp", "dlmc", "dlmc_code", "direction",
    "location_mark_start", "location_mark_end", "mileage_end", "mileage_start_longitude",
    "mileage_start_latitude", "mileage_end_longitude", "mileage_end_latitude",
    "small_road_length", "mileage_start", "big_roadclid", "big_road_name", "big_road_type",
    "big_froadname", "big_troadname", "big_fnode", "big_tnode", "big_path", "big_length",
    "agg_big_roadclid", "agg_big_road_length", "agg_big_mileage_start", "agg_big_mileage_end",
    "sgss",
]
mutate_empty = [empty(name) for name in empty_names]
mutate_names = [fld(name, "VARCHAR", NAMES) for name in ["kcr1", "kcr2", "bar1", "bar2"]]

acd_file_fields = seed_fields + mutate_core + mutate_empty + mutate_names
acd_file_mutate = [field["name"] for field in mutate_core + mutate_empty + mutate_names]

ds_mutate_names = [
    "sgbh", "djbh", "xq", "sgfssj", "lh", "gls", "ms", "jdwz", "ssrs", "zjccss", "rdyyfl",
    "sgrdyy", "tq", "sgxt", "cljsg", "pzfs", "lbqk", "dzzb", "sgss", "glxzdj", "jd", "wd",
    "msg", "swrs7_1", "swrs_end", "all_jtfs_type", "zonename", "rdlv", "node_id", "tysgzp",
    "dlmc", "dlmc_code", "direction", "location_mark_start", "location_mark_end",
    "mileage_start", "mileage_end", "mileage_start_longitude", "mileage_start_latitude",
    "mileage_end_longitude", "mileage_end_latitude", "small_road_length", "tjr1", "jar1",
    "jar2", "jbr", "big_roadclid", "big_road_name", "big_road_type", "big_froadname",
    "big_troadname", "big_fnode", "big_tnode", "big_path", "big_length", "agg_big_roadclid",
    "agg_big_road_length", "agg_big_mileage_start", "agg_big_mileage_end", "glbm",
    "glbm_name", "my_dt",
]
fields_map = {field["name"]: field for field in acd_file_fields}
for name in ["tjr1", "jar1", "jar2", "jbr"]:
    fields_map[name] = fld(name, "VARCHAR", NAMES)
acd_dutysimple_fields = seed_fields + [fields_map[name] for name in ds_mutate_names]


class LiteralDumper(yaml.SafeDumper):
    pass


def _str_presenter(dumper, data):
    if "\n" in data:
        return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", data)


LiteralDumper.add_representer(str, _str_presenter)

output = {
    "acd_road_seed_reader": {
        "type": "postgresql",
        "connection": "dev-road",
        "query": SEED_QUERY,
    },
    "acd_file_mutate": acd_file_mutate,
    "acd_file_fields": acd_file_fields,
    "acd_dutysimple_mutate": ds_mutate_names,
    "acd_dutysimple_fields": acd_dutysimple_fields,
}

target = (
    r"e:\探索\data-generator\dg-web\src\main\resources\configs\jobs\_acd_generated.yaml"
)
with open(target, "w", encoding="utf-8") as handle:
    yaml.dump(output, handle, Dumper=LiteralDumper, allow_unicode=True, sort_keys=False, width=120)

print(f"written {target}")
