#!/usr/bin/env python3
"""能力结构导入 E2E 矩阵（方案 14 §十 验收表）。

直接打预览 / 写入 / 撤销接口，验证后端确定性规则与前端依赖的口径；不经过 agent
（agent 链路在 S2/S3 用真实工作簿走过页面，这里不重复）。
脚本自带前后清场，可反复跑；跑完库里的计数应与开跑前一致。

前置：
  - 后端在 8080、前端可在 3000（本脚本不打前端）
  - 装好 pymssql / PyYAML：pip3 install pymssql pyyaml
  - 有一份可用的测评 JWT（默认用仓库自带的 dev 签发脚本，见 TOKEN 行）

用法：
  python3 e2e/structure-import-e2e.py
"""
import json
import sys
import time
import urllib.error
import urllib.request

import jwt
import pymssql
import yaml

REPO = '/home/wgd/ws/ability-assessment'
with open(REPO + '/backend/config/secret.yml') as _f:
    _S = yaml.safe_load(_f)

CFG = dict(server='172.18.28.108', port=1433, user='sa',
           password=_S['spring']['datasource']['password'], database='ecology')
BASE = 'http://localhost:8080/api/v1'
TAG = 'S4'
# 交付稿 payload_json 以 {"kind" 开头，用前缀匹配永远命中不了，只能子串匹配
TAG_LIKE = '%' + TAG + '%'
TAG_P = TAG + '%'
# dimension 3 下还没有人员能力要求的人：避免命中 UPDATE 分支（那个由用例 6 单独验）
TEST_LOGIN = '2012001'

# 直接按后端的 jwt.secret 现签一个 2 小时的管理员令牌，省得手工贴 token
TOKEN = jwt.encode({'sub': '2011004', 'uid': 2505, u'name': u'吴雪艳', 'dept': 1, 'subco': 1,
                    'iat': int(time.time()), 'exp': int(time.time()) + 7200},
                   _S['jwt']['secret'], algorithm='HS256')
HDR = {'Authorization': 'Bearer ' + TOKEN, 'X-Dimension-Id': '3', 'Content-Type': 'application/json'}

results = []


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=HDR, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read())


def check(name, cond, detail=''):
    results.append((name, bool(cond)))
    print(('  PASS  ' if cond else '  FAIL  ') + name + ('' if cond else '  <<< ' + str(detail)[:400]))


def conn():
    return pymssql.connect(**CFG)


def plumb(seq):
    """一份自洽的结构交付稿；seq 区分用例，避免相互干扰"""
    cat, el = TAG + '类别' + seq, TAG + '要素' + seq
    return {
        'kind': 'ABILITY_STRUCTURE', 'dimensionId': 3, 'year': 2026,
        'categories': [{'name': cat, 'sort': 95}],
        'elements': [{'categoryName': cat, 'name': el,
                      'levels': [{'levelNo': 1, 'desc': seq + '一级描述'},
                                 {'levelNo': 2, 'desc': seq + '二级描述'}]}],
        'positionReqs': [{'departmentName': '集团安环部', 'jobTitleName': '安全专员',
                          'items': [{'categoryName': cat, 'elementName': el, 'weight': 100}]}],
        'userReqs': [{'resourceNo': TEST_LOGIN, 'items': [{'elementName': el, 'levelNo': 2}]}],
        'coveredRows': [u'能力素质行为表现库!2'], 'skipped': [], 'unreconciled': False,
    }


def make_draft(payload):
    """建 run + 插一份 READY 交付稿，返回 (runId, draftId)"""
    run = call('POST', '/external/import-runs', {'kind': 'ABILITY_STRUCTURE'})['data']
    payload['sessionId'] = run['sessionId']
    c = conn()
    cur = c.cursor()
    cur.execute("INSERT INTO uf_assess_agent_draft (session_id, company_id, year, dimension_id, payload_json,"
                " status, ready_time, last_seen_time, kind)"
                " VALUES (%s, 1, 2026, 3, %s, 'READY', GETDATE(), GETDATE(), 'ABILITY_STRUCTURE')",
                (run['sessionId'], json.dumps(payload, ensure_ascii=False)))
    cur.execute("SELECT MAX(id) FROM uf_assess_agent_draft WHERE session_id=%s", (run['sessionId'],))
    did = cur.fetchone()[0]
    cur.execute("UPDATE uf_assess_import_run SET status='READY', draft_id=%s WHERE id=%s", (did, run['id']))
    c.commit()
    c.close()
    return run['id'], did


TABLES = ('uf_ability_category', 'uf_ability_element', 'uf_ability_element_level',
          'uf_position_ability_req_new', 'uf_user_ability_req_new')


def table_counts():
    c = conn()
    cur = c.cursor()
    out = {}
    for t in TABLES:
        cur.execute('SELECT COUNT(*) FROM ' + t)
        out[t] = cur.fetchone()[0]
    c.close()
    return out


def purge_all():
    """删掉本脚本造的所有数据（含上次跑崩留下的）"""
    c = conn()
    cur = c.cursor()
    # 先把上次跑崩留下的批次会话关掉，否则内存里的 ACTIVE 名额要等 30 分钟才回收
    cur.execute("SELECT id FROM uf_assess_import_run WHERE draft_id IN "
                "(SELECT id FROM uf_assess_agent_draft WHERE payload_json LIKE %s)", (TAG_LIKE,))
    for (rid,) in cur.fetchall():
        c.close()
        call('POST', '/external/import-runs/%d/close' % rid, {'dropUploads': True})
        c = conn()
        cur = c.cursor()
    cur.execute("SELECT id FROM uf_ability_element WHERE element_name LIKE %s", (TAG_P,))
    eids = [r[0] for r in cur.fetchall()]
    cur.execute("SELECT id FROM uf_ability_category WHERE category_name LIKE %s", (TAG_P,))
    cids = [r[0] for r in cur.fetchall()]
    if eids:
        q = ','.join(['%s'] * len(eids))
        cur.execute("SELECT req_id FROM uf_user_ability_req_item WHERE element_id IN (%s)" % q, tuple(eids))
        ur_ids = sorted({r[0] for r in cur.fetchall()})
        cur.execute("SELECT req_id FROM uf_position_ability_req_item WHERE element_id IN (%s)" % q, tuple(eids))
        pr_ids = sorted({r[0] for r in cur.fetchall()})
        cur.execute("DELETE FROM uf_user_ability_req_item WHERE element_id IN (%s)" % q, tuple(eids))
        cur.execute("DELETE FROM uf_position_ability_req_item WHERE element_id IN (%s)" % q, tuple(eids))
        for rid in ur_ids:
            cur.execute("DELETE FROM uf_user_ability_req_new WHERE id=%s", (rid,))
        for rid in pr_ids:
            cur.execute("DELETE FROM uf_position_ability_req_new WHERE id=%s", (rid,))
        cur.execute("DELETE FROM uf_ability_element_level WHERE element_id IN (%s)" % q, tuple(eids))
        cur.execute("DELETE FROM uf_ability_element WHERE element_name LIKE %s", (TAG_P,))
    for cid in cids:
        cur.execute("DELETE FROM uf_ability_category WHERE id=%s", (cid,))
    cur.execute("DELETE FROM uf_assess_import_batch_item WHERE batch_id IN "
                "(SELECT id FROM uf_assess_import_run WHERE draft_id IN "
                "(SELECT id FROM uf_assess_agent_draft WHERE payload_json LIKE %s))", (TAG_LIKE,))
    cur.execute("DELETE FROM uf_assess_import_run WHERE draft_id IN "
                "(SELECT id FROM uf_assess_agent_draft WHERE payload_json LIKE %s)", (TAG_LIKE,))
    cur.execute("DELETE FROM uf_assess_agent_draft WHERE payload_json LIKE %s", (TAG_LIKE,))
    c.commit()
    c.close()


print('S4 E2E 矩阵\n' + '=' * 74)
purge_all()
print('（已清场，开始跑）\n')
made = []
baseline = table_counts()

# ---- 1. preview 只读 ----
run_a, draft_a = make_draft(plumb('A')); made.append(run_a)
pv = call('GET', '/agent/drafts/%d/preview' % draft_a)['data']
check('preview 不写库（5 张表计数前后一致）', table_counts() == baseline, table_counts())
check('preview 可提交（无阻断）', pv['commitable'] is True, pv['counts'])
check('preview 计数：1 类别 / 1 要素 / 2 等级 / 1 岗位 / 1 人员',
      (pv['counts']['categoryCreate'], pv['counts']['elementCreate'], pv['counts']['levelCreate'],
       pv['counts']['positionCreate'], pv['counts']['userCreate']) == (1, 1, 2, 1, 1), pv['counts'])

# ---- 2. 写入 ----
cm = call('POST', '/agent/drafts/%d/commit' % draft_a)
call('POST', '/external/import-runs/%d/close' % run_a, {'dropUploads': False})
check('commit 成功', cm['code'] == 200 and not cm['data'].get('rejected'), cm)
r1 = cm['data']['result']
check('commit 回执计数正确',
      (r1['counts']['categoryCreate'], r1['counts']['elementCreate'], r1['counts']['levelCreate'],
       r1['counts']['positionCreate'], r1['counts']['userCreate']) == (1, 1, 2, 1, 1), r1['counts'])
check('commit 回执带 batchId', bool(r1.get('batchId')), r1.get('batchId'))
c = conn(); cur = c.cursor()
cur.execute("SELECT level_requirement FROM uf_ability_element_level WHERE element_id IN "
            "(SELECT id FROM uf_ability_element WHERE element_name=%s) ORDER BY level", (TAG + '要素A',))
descs = [r[0] for r in cur.fetchall()]
c.close()
check('等级描述落 level_requirement、顺序内容正确（不是分值）', descs == [u'A一级描述', u'A二级描述'], descs)

# ---- 3. 重复 commit ----
cm2 = call('POST', '/agent/drafts/%d/commit' % draft_a)
check('重复 commit 被拒（已提交）', cm2['code'] != 200 and u'已提交' in str(cm2.get('message')), cm2)

# ---- 4. 同稿再传 → 全部跳过、写 0 条 ----
run_b, draft_b = make_draft(plumb('A')); made.append(run_b)
c2 = call('GET', '/agent/drafts/%d/preview' % draft_b)['data']['counts']
check('同一本表再传一次：类别/要素/等级/岗位全标跳过',
      (c2['categoryCreate'], c2['elementCreate'], c2['levelCreate'], c2['positionCreate']) == (0, 0, 0, 0)
      and (c2['categorySkip'], c2['elementSkip'], c2['positionKeep']) == (1, 1, 1), c2)
r2 = call('POST', '/agent/drafts/%d/commit' % draft_b)['data']['result']['counts']
call('POST', '/external/import-runs/%d/close' % run_b, {'dropUploads': False})
check('第二次写入 0 条新增（不产生重复实体）',
      (r2['categoryCreate'], r2['elementCreate'], r2['levelCreate'], r2['positionCreate']) == (0, 0, 0, 0), r2)

# ---- 5. 撤销 ----
un = call('POST', '/external/import-runs/%d/undo' % run_a, {})
check('撤销只删本批 CREATE（6 个实体）', un['code'] == 200 and un['data']['removed'] == 6, un)
c = conn(); cur = c.cursor()
cur.execute("SELECT COUNT(*) FROM uf_ability_category WHERE category_name=%s", (TAG + '类别A',))
n_cat = cur.fetchone()[0]
cur.execute("SELECT COUNT(*) FROM uf_ability_element WHERE element_name=%s", (TAG + '要素A',))
n_el = cur.fetchone()[0]
c.close()
check('撤销后类别与要素都已删除', n_cat == 0 and n_el == 0, (n_cat, n_el))

# ---- 6. force 闸：重复写同一份稿会产生 UPDATE ----
run_c, draft_c = make_draft(plumb('C')); made.append(run_c)
call('POST', '/agent/drafts/%d/commit' % draft_c)
call('POST', '/external/import-runs/%d/close' % run_c, {'dropUploads': False})
run_d, draft_d = make_draft(plumb('C')); made.append(run_d)
call('POST', '/agent/drafts/%d/commit' % draft_d)
call('POST', '/external/import-runs/%d/close' % run_d, {'dropUploads': False})
c = conn(); cur = c.cursor()
cur.execute("SELECT action FROM uf_assess_import_batch_item WHERE batch_id=%s", (run_d,))
acts = [r[0] for r in cur.fetchall()]
c.close()
if 'UPDATE' in acts:
    un2 = call('POST', '/external/import-runs/%d/undo' % run_d, {})
    check('含覆盖写的批次默认拒绝撤销', un2['code'] == 409 and u'覆盖写' in str(un2.get('message')), un2)
    un3 = call('POST', '/external/import-runs/%d/undo' % run_d, {'force': True})
    check('显式 force 才放行', un3['code'] == 200, un3)
else:
    check('含覆盖写的批次默认拒绝撤销', False, '本批未产生 UPDATE，动作=' + str(acts))
    check('显式 force 才放行', False, 'skipped')
un4 = call('POST', '/external/import-runs/%d/undo' % run_c, {})
check('撤销其余批次不影响别的批次', un4['code'] == 200 and un4['data']['removed'] >= 1, un4)

# ---- 7. BLOCK：权重越界 ----
p = plumb('E'); p['positionReqs'][0]['items'][0]['weight'] = 150
run_e, draft_e = make_draft(p); made.append(run_e)
call('POST', '/external/import-runs/%d/close' % run_e, {'dropUploads': False})
pv3 = call('GET', '/agent/drafts/%d/preview' % draft_e)['data']
check('权重 150 → 阻断且不可提交', pv3['commitable'] is False and pv3['counts']['blocked'] >= 1, pv3['counts'])
cm5 = call('POST', '/agent/drafts/%d/commit' % draft_e)
check('阻断稿 commit 被拒', cm5['code'] != 200, cm5)

# ---- 8. 未开测评期 ----
p = plumb('F'); p['year'] = 2019
run_f, draft_f = make_draft(p); made.append(run_f)
call('POST', '/external/import-runs/%d/close' % run_f, {'dropUploads': False})
pv4 = call('GET', '/agent/drafts/%d/preview' % draft_f)['data']
check('年度不存在 → BLOCK', pv4['commitable'] is False and pv4['counts']['blocked'] >= 1, pv4['counts'])

# ---- 9. 查无此人 → PENDING，不阻断 ----
p = plumb('G'); p['userReqs'] = [{'resourceName': u'绝不存在的名字XYZ',
                                  'items': [{'elementName': TAG + '要素G', 'levelNo': 2}]}]
run_g, draft_g = make_draft(p); made.append(run_g)
call('POST', '/external/import-runs/%d/close' % run_g, {'dropUploads': False})
pv5 = call('GET', '/agent/drafts/%d/preview' % draft_g)['data']
check('查无此人 → PENDING 且不阻断整批',
      pv5['counts']['userPending'] == 1 and pv5['commitable'] is True, pv5['counts'])

# ---- 10. 维度不一致 → 拒绝 ----
p = plumb('H'); p['dimensionId'] = 4
run_h, draft_h = make_draft(p); made.append(run_h)
call('POST', '/external/import-runs/%d/close' % run_h, {'dropUploads': False})
pv6 = call('GET', '/agent/drafts/%d/preview' % draft_h)
check('交付稿维度与当前维度不一致 → 拒绝', pv6['code'] != 200 and u'维度' in str(pv6.get('message')), pv6)

# ---- 清场 ----
print('\n清场…')
for rid in made:
    call('POST', '/external/import-runs/%d/undo' % rid, {'force': True})
    call('POST', '/external/import-runs/%d/close' % rid, {'dropUploads': True})
purge_all()
left = table_counts()
print('清场后与开跑前一致：', left == baseline, '' if left == baseline else (baseline, left))

print('\n' + '=' * 74)
ok = sum(1 for _, c_ in results if c_)
print('结果：%d/%d 通过' % (ok, len(results)))
sys.exit(0 if ok == len(results) else 1)
