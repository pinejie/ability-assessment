// 系统管理员 VO（与后端 AssessAdminVO 对应）
export interface AssessAdminVO {
  /** 表记录 ID；配置超管未落表时为空 */
  id: number | null
  /** OA 登录名 */
  loginid: string
  /** 姓名（查泛微人员表，查不到为空） */
  lastname?: string
  remark?: string
  /** 配置超管（assess.bootstrap-admins）：不可界面删除 */
  bootstrap: boolean
  createTime?: string
}

// 新增管理员 DTO（按泛微人员 ID，loginid 由服务端反查）
export interface AssessAdminCreateDTO {
  resourceId: number
  remark?: string
}
