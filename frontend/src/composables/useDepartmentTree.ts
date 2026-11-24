import { ref } from 'vue'
import request from '@/utils/request'
import { getCurrentDimensionScope } from '@/api/assessDimension'
import type { RawSubCompany, RawDepartment } from '@/types/org'

/**
 * 部门树节点：公司/部门两类共用一棵树。
 * 岗位只能挂在部门上，所以节点带 type，选择器据此禁掉公司节点。
 */
export interface DeptTreeNode {
  id: number
  name: string
  type: 'company' | 'department'
  children?: DeptTreeNode[]
}

/**
 * 泛微组织树（公司 → 子部门），供岗位/人员映射等页面的部门选择器复用。
 *
 * 候选范围跟着当前维度的「授权分部」走（2026-11-23）：岗位只能挂在本维度授权的分部下面。
 * 之前这里是拉全公司 371 个部门，配了外分部的部门也能建岗位 —— 建出来的岗位属于本维度，
 * 人却是外分部的，等级没有业务含义，等于给了一条越权入口。
 *
 * 一次拉全量再在浏览器里拼树：泛微的 children 接口是逐个节点懒加载的，
 * 这里的数据规模（百级公司 + 千级部门）一次拉完更快，也让 filterable 搜索能覆盖全树。
 */
export function useDepartmentTree() {
  const treeData = ref<DeptTreeNode[]>([])
  const loading = ref(false)
  const deptList = ref<DeptTreeNode[]>([])
  /** 该维度是否受分部授权限制（false = 不限，部门树是全量） */
  const restricted = ref(false)

  const load = async (): Promise<void> => {
    if (loading.value) return
    loading.value = true
    try {
      const [companyResult, deptResult, scope] = await Promise.all([
        request.get('/sub-companies', { params: { pageNum: 1, pageSize: 100 } }),
        request.get('/departments', { params: { pageNum: 1, pageSize: 1000 } }),
        // 范围接口失败不能让整棵树消失：拿不到就当「不限」，至少不阻断原有用法
        getCurrentDimensionScope().catch(() => null),
      ])
      const companies: RawSubCompany[] = companyResult.list || []
      const departments: RawDepartment[] = deptResult.list || []

      restricted.value = scope?.restricted === true
      // restricted=false 时不裁剪（该维度没配授权分部，通常只有管理员能进）
      const allowedSubs = restricted.value ? new Set(scope!.subcompanyIds) : null
      const allowedDepts = restricted.value ? new Set(scope!.departmentIds) : null

      const companyMap = new Map<number, DeptTreeNode>()
      companies.forEach((c) => {
        if (allowedSubs && !allowedSubs.has(c.id)) return
        companyMap.set(c.id, { id: c.id, name: c.subcompanyname, type: 'company', children: [] })
      })

      const roots: DeptTreeNode[] = []
      companies.forEach((c) => {
        const node = companyMap.get(c.id)
        if (!node) return
        const parent = c.supsubcomid ? companyMap.get(c.supsubcomid) : undefined
        if (parent && parent.id !== node.id) {
          parent.children!.push(node)
        } else {
          roots.push(node)
        }
      })

      const deptMap = new Map<number, DeptTreeNode>()
      departments.forEach((d) => {
        if (allowedDepts && !allowedDepts.has(d.id)) return
        deptMap.set(d.id, { id: d.id, name: d.departmentmark, type: 'department', children: [] })
      })
      deptList.value = [...deptMap.values()]

      departments.forEach((d) => {
        const node = deptMap.get(d.id)
        if (!node) return
        const parentDept = d.supdepid ? deptMap.get(d.supdepid) : undefined
        if (parentDept) {
          parentDept.children!.push(node)
        } else {
          const company = d.subcompanyid1 != null ? companyMap.get(d.subcompanyid1) : undefined
          if (company) {
            company.children!.push(node)
          } else {
            // 上级部门/in 范围外的分部兜不住时挂到根，宁可多一个顶层节点也不要让部门凭空消失
            roots.push(node)
          }
        }
      })

      treeData.value = roots
    } catch (error) {
      console.error('加载部门树失败:', error)
    } finally {
      loading.value = false
    }
  }

  return { treeData, deptList, loading, restricted, load }
}

/** el-tree-select / el-tree 的通用 props：只有部门节点可选 */
export const deptTreeProps = {
  label: 'name',
  value: 'id',
  children: 'children',
  disabled: (data: DeptTreeNode) => data.type !== 'department',
}

/**
 * 在已加载的部门树里按 id 找部门名。
 * 列表页要显示部门名，但接口已经带回快照 departmentName，这里只给「回填编辑表单」用。
 */
export const findDeptNode = (nodes: DeptTreeNode[], id: number): DeptTreeNode | undefined => {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = n.children ? findDeptNode(n.children, id) : undefined
    if (hit) return hit
  }
  return undefined
}
