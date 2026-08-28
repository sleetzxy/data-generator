#!/usr/bin/env python3
"""将 SQLite 中 jobs/ 配置路径迁移为 task-configs/。"""
import sqlite3
import sys
from pathlib import Path

db_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("data/dg-tasks.db")
if not db_path.exists():
    print(f"数据库不存在: {db_path}")
    sys.exit(0)

conn = sqlite3.connect(db_path)
cur = conn.cursor()
r1 = cur.execute(
    "UPDATE task_runs SET config_path = REPLACE(config_path, 'jobs/', 'task-configs/') "
    "WHERE config_path LIKE 'jobs/%'"
).rowcount
r2 = cur.execute(
    "UPDATE task_schedules SET config_path = REPLACE(config_path, 'jobs/', 'task-configs/') "
    "WHERE config_path LIKE 'jobs/%'"
).rowcount
conn.commit()
print(f"task_runs 更新 {r1} 行, task_schedules 更新 {r2} 行")
for row in cur.execute("SELECT run_id, config_path FROM task_runs LIMIT 5"):
    print(" run:", row)
for row in cur.execute("SELECT config_path FROM task_schedules"):
    print(" schedule:", row)
conn.close()
