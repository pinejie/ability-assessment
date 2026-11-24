#!/usr/bin/env python3
"""从 /tmp/pa_sheets.json（Excel 解析结果）导入两套岗位能力模型到 dimension 3。

只调固定接口，不直连库：
  POST /pa/posts                       建岗位
  POST /pa/posts/{id}/categories       建能力类别
  POST /pa/posts/{id}/items            建能力项 + 5 档等级（一次事务）
  PUT  /pa/posts/{id}/bands            整批写分值区间

口径：
  · 权重 Excel 写 0.14，库里口径是百分数 → ×100 = 14
  · 等级名用表头整段（「入门/新手」），业务方要求就这样显示
  · 档位名取斜杠后半（新手/熟手/能手/高手/专家），区间左闭右开
  · 评价主体：「直接上级」只上级，「内部客户360」只 360，带「＋」的两者都是
"""
import json
import re
import sys
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080/api/v1"
DEPARTMENT_ID = 57          # 数字化推进办公室（subcompany 1 = 中达集团）
DIMENSION_ID = 3            # 中达集团
TOKEN = open("/tmp/pa-admin-token.txt").read().strip()

SHEETS = json.load(open("/tmp/pa_sheets.json"))

# 表头列 → 分值
LEVEL_COLS = [("E", 1), ("F", 2), ("G", 3), ("H", 4), ("I", 5)]

# 档位名字取「1分 · 入门/新手」斜杠后的那半
BAND_NAMES = ["新手", "熟手", "能手", "高手", "专家"]


def call(method, path, body=None):
    url = BASE + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("X-Dimension-Id", str(DIMENSION_ID))
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> HTTP {e.code}: {e.read().decode()[:800]}")
    if payload.get("code") not in (200, 0):
        sys.exit(f"{method} {path} -> {payload}")
    return payload.get("data")


def rater_flags(text):
    """评价主体文字 → (raterLeader, rater360)"""
    t = (text or "").replace(" ", "")
    has_leader = "上级" in t
    has_360 = "360" in t
    if not has_leader and not has_360:
        sys.exit(f"评价主体无法识别: {text!r}")
    return (1 if has_leader else 0, 1 if has_360 else 0)


def weight_percent(raw):
    """Excel 0.14 → 库内百分数 14"""
    return round(float(raw) * 100, 4)


def build(sheet_name):
    """把一张表的行拆成 类别列表 + 能力项列表"""
    rows = SHEETS[sheet_name]["rows"]
    header = next(r for r in rows if r.get("B") == "编号")
    level_names = {col: header[col].split("·", 1)[-1].strip() for col, _ in LEVEL_COLS}
    print(f"  [{sheet_name}] 等级名: {level_names}")

    categories, items = [], []
    for r in rows:
        code = r.get("B")
        if not code or not re.fullmatch(r"[A-Z]\d+", code or ""):
            continue
        cat = r["A"].strip()
        if cat not in categories:
            categories.append(cat)
        items.append({
            "category": cat,
            "itemCode": code,
            "itemName": r["C"].strip(),
            "definition": (r.get("D") or "").strip(),
            "weight": weight_percent(r["J"]),
            "rater": rater_flags(r.get("K")),
            "levels": [
                {
                    "levelName": level_names[col],
                    "score": score,
                    "levelDesc": (r.get(col) or "").strip(),
                    "sort": idx,
                }
                for idx, (col, score) in enumerate(LEVEL_COLS)
            ],
        })
    total = sum(i["weight"] for i in items)
    print(f"  [{sheet_name}] {len(categories)} 类别 / {len(items)} 能力项 / 权重合计 {total:g}%")
    if abs(total - 100) > 1e-6:
        sys.exit(f"权重合计不是 100%：{sheet_name} = {total}")
    return categories, items


def import_sheet(sheet_name, post_name, sort):
    print(f"\n=== 导入「{post_name}」 ===")
    categories, items = build(sheet_name)

    post_id = call("POST", "/pa/posts", {
        "departmentId": DEPARTMENT_ID,
        "postName": post_name,
        "description": f"{sheet_name}能力素质模型（来自业务方 Excel）",
        "status": 1,
        "sort": sort,
    })
    print(f"  岗位 id = {post_id}")

    cat_ids = {}
    for idx, name in enumerate(categories):
        cat_ids[name] = call("POST", f"/pa/posts/{post_id}/categories", {
            "categoryName": name,
            "sort": idx,
            "status": 1,
        })
    print(f"  类别 id = {cat_ids}")

    for idx, item in enumerate(items):
        leader, r360 = item["rater"]
        item_id = call("POST", f"/pa/posts/{post_id}/items", {
            "categoryId": cat_ids[item["category"]],
            "itemCode": item["itemCode"],
            "itemName": item["itemName"],
            "definition": item["definition"],
            "weight": item["weight"],
            "raterLeader": leader,
            "rater360": r360,
            "sort": idx,
            "status": 1,
            "levels": item["levels"],
        })
        print(f"  {item['itemCode']} {item['itemName']} 权重 {item['weight']:g}% "
              f"主体 leader={leader} 360={r360} → item id {item_id}")

    bands = [
        {"bandName": BAND_NAMES[i], "lowerBound": score,
         "upperBound": (score + 1 if score < 5 else None), "sort": i}
        for i, (_, score) in enumerate(LEVEL_COLS)
    ]
    call("PUT", f"/pa/posts/{post_id}/bands", bands)
    print(f"  区间 = {[(b['bandName'], b['lowerBound'], b['upperBound']) for b in bands]}")
    return post_id


def main():
    p1 = import_sheet("技术人员", "技术人员", 0)
    p2 = import_sheet("信息化管理人员", "信息化管理人员", 1)
    print(f"\n完成：post_id = {p1}(技术人员), {p2}(信息化管理人员)")


if __name__ == "__main__":
    main()
