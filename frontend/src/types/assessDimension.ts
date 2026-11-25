// 测评维度 VO（类租户）
export interface AssessDimensionVO {
  id: number
  dimensionName: string
  dimensionCode?: string
  description?: string
  status: number
  sort: number
  /** 授权分部（sql/58 合并）：既决定归属哪几家，也决定这些分部的人可用 */
  subcompanys?: AssessDimensionAuthItem[]
  /** 排除分部（sql/59）：在授权范围之内、但不归本维度的下级分支（含其下级） */
  excludedSubcompanys?: AssessDimensionAuthItem[]
  /** 授权人员：这些人可用，同时是维度管理员 —— 能看测评报告里 360 测评人的真实姓名 */
  persons?: AssessDimensionAuthItem[]
  /** 授权条目数（= 分部数 + 人员数）。0 表示只有系统管理员能进 */
  authCount: number
  createTime: string
}

/** 授权对象条目（分部 / 人员共用） */
export interface AssessDimensionAuthItem {
  id: number
  name?: string | null
}

// 维度创建 DTO
export interface AssessDimensionCreateDTO {
  dimensionName: string
  dimensionCode?: string
  description?: string
  status?: number
  sort?: number
}

// 维度更新 DTO
export interface AssessDimensionUpdateDTO {
  id: number
  dimensionName?: string
  dimensionCode?: string
  description?: string
  status?: number
  sort?: number
}

// 维度授权条目 VO
export interface AssessDimensionAuthVO {
  id: number
  authType: number
  authId: number
  authName?: string
}

// 维度授权保存条目
export interface DimensionAuthItemDTO {
  // 1-分部，2-人员，3-排除分部
  authType: number
  authId: number
}

export const AUTH_TYPE_SUBCOMPANY = 1
export const AUTH_TYPE_RESOURCE = 2
/** 排除分部：在授权范围之内、但不归本维度的分支（含其下级） */
export const AUTH_TYPE_EXCLUDED_SUBCOMPANY = 3

/**
 * 当前维度的组织范围（部门/人员候选收窄用）。
 *
 * `restricted=false` 表示该维度没配「授权分部」，不做范围收窄 —— 必须按这个开关判断，
 * 不能拿 `departmentIds` 为空当作「没有部门」，那是两件不同的事。
 */
export interface DimensionScopeVO {
  restricted: boolean
  /** 授权分部（已展开子树、已扣掉排除分部） */
  subcompanyIds: number[]
  /** 授权分部下的部门 ID */
  departmentIds: number[]
}

// ==================== 集成配置（单点登录 + 消息推送） ====================

/**
 * 密钥占位符。
 *
 * 后端从不回显真实密钥，读接口一律返回这个字符串。保存时原样传回去 = 「不修改」，
 * 传空串 = 「清空」，传新值 = 「覆盖」。三个语义必须分清，写错一个就会把密钥存成
 * 字面量 "__UNCHANGED__"，下次登录拿它去换 token 才炸。
 */
export const SECRET_MASK = '__UNCHANGED__'

/**
 * 单点登录协议在配置页上的一个接口地址字段（后端 SsoField 的镜像）。
 *
 * `key` 决定绑哪个表单字段（authorizeUrl / tokenUrl / profileUrl），页面按它循环渲染，
 * 不按协议名写死「三个地址框」—— 票据模式只有两个接口，第三个框不该出现。
 */
export interface SsoField {
  key: string
  label: string
  /** 本协议下是否必填（由后端保存时校验，前端用它显示红星） */
  required: boolean
  hint?: string | null
}

/**
 * 协议在配置页上的形态（后端 SsoShape 的镜像）。
 *
 * `derivableFromBase=false` 时页面隐藏「认证中心根地址」——票据模式没有认证中心这个概念。
 * 注意它**不再**意味着「地址可以留空让后端推导」：所有地址都必须自己填，
 * 根地址只用于拼注销地址和「按根地址填上面三个」这个省手工的按钮。
 */
export interface SsoShape {
  fields: SsoField[]
  /** 兑换票据后是否还要再调一次接口取身份（泛微 true / 票据模式 false） */
  accessTokenStep: boolean
  /** 本协议是否有「根地址 + 固定路径」这套规律（决定显示不显示根地址框） */
  derivableFromBase: boolean
}

/** 维度集成配置（读） */
export interface DimensionIntegrationVO {
  dimensionId: number
  dimensionName?: string
  /** false 表示库里还没有配置行，展示的是空壳 */
  configured: boolean
  /**
   * 这个维度的单点登录能不能用。
   *
   * <p>由配置算出来的（client_id 与 client_secret 都在才算 true），不是库里那个
   * sso_enabled 列 —— 那个开关已经删了，页面也不该再显示它。</p>
   */
  ssoEnabled: boolean
  ssoProviderType?: string
  ssoClientId?: string
  /** 配了就返回 SECRET_MASK，没配是 null */
  ssoClientSecret?: string | null
  ssoBaseUrl?: string
  ssoAuthorizeUrl?: string
  ssoTokenUrl?: string
  ssoProfileUrl?: string
  ssoUserinfoField?: string
  /** 回调 URL 里票据的参数名；没配就是 null（后端不再用协议默认填） */
  ssoCallbackParam?: string | null
  /** GET / POST */
  ssoTokenMethod?: string | null
  ssoProfileMethod?: string | null
  ssoAccessTokenField?: string | null
  /** loginid（OA 登录名）或 workcode（工号） */
  ssoMatchField?: string
  ssoRedirectPath?: string
  // 协议固定路径：只用于「按根地址填上面三个」按钮代填，不参与保存
  defaultAuthorizePath?: string
  defaultTokenPath?: string
  defaultProfilePath?: string
  /**
   * 当前协议在配置页上的形态：用哪几个地址、要不要 access_token 那一步。
   * 页面照着它渲染，不按协议名写死 —— 接第二个协议时页面不用改。
   */
  ssoShape?: SsoShape
  /** 该维度实际会用的完整回调地址，直接复制给对方的白名单；站点根没配时为 null */
  callbackUrl?: string | null
  pushEnabled: boolean
  pushChannel?: string
  pushAgentId?: string
  pushSecret?: string | null
  pushExtraJson?: string
}

/** 维度集成配置（写）。密钥三语义见 SECRET_MASK 注释 */
export interface DimensionIntegrationSaveDTO {
  dimensionId: number
  /**
   * @deprecated 后端已不再读这个字段（2026-11-25 删除「启用单点登录」开关）：
   * 能不能用由 ssoClientSecret 是否为空决定。字段保留只为兼容旧调用。
   */
  ssoEnabled?: boolean
  ssoProviderType: string
  ssoClientId: string
  ssoClientSecret?: string | null
  ssoBaseUrl?: string
  ssoAuthorizeUrl?: string
  ssoTokenUrl?: string
  ssoProfileUrl?: string
  ssoUserinfoField?: string
  ssoCallbackParam?: string
  ssoTokenMethod?: string
  ssoProfileMethod?: string
  ssoAccessTokenField?: string
  ssoMatchField: string
  ssoRedirectPath?: string
  pushEnabled?: boolean
  pushChannel: string
  pushAgentId?: string
  pushSecret?: string | null
  pushExtraJson?: string
}

/** 下拉可选项 */
/**
 * 一个协议的全部描述（后端 ProviderOption 的镜像）。
 *
 * 切换协议时页面要一起变好几样东西：显示哪几个地址框、「按根地址填上面三个」按钮能不能点。
 * 打包成一条随 options 一次全给，避免切换过程中出现「框换了、按钮还在」的中间态。
 *
 * 2026-11-25 删掉了四个「默认值」字段（默认回调参数名 / 两种请求方式 / 取码字段名）：
 * 它们只用来做 placeholder，而 placeholder 会让人以为那一栏已经配好了。
 */
export interface ProviderOptionVO {
  shape: SsoShape
  /** null 表示本协议没有这个固定路径 */
  defaultAuthorizePath: string | null
  defaultTokenPath: string | null
  defaultProfilePath: string | null
}

export interface IntegrationOptions {
  providerTypes: string[]
  /** 每个协议各自的形态与默认值，key 是协议类型名 */
  providerOptions?: Record<string, ProviderOptionVO>
  matchFields: string[]
  pushChannels: string[]
  /** false 表示服务端没配 integration.master-key，此时保存密钥会失败 */
  secretCipherReady: boolean
}
