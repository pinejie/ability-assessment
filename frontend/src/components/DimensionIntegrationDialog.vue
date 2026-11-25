<template>
  <el-dialog
    v-model="visible"
    :title="`集成配置 — ${dimensionName}`"
    width="720px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <div v-loading="loading" class="integration-body">
      <el-alert
        v-if="!secretCipherReady"
        type="error"
        :closable="false"
        show-icon
        title="服务端未配置 integration.master-key，密钥无法加密保存"
        description="请先在 backend/config/secret.yml 或环境变量 INTEGRATION_MASTER_KEY 配置主密钥并重启后端，否则保存会被拒绝。"
        class="body-alert"
      />
      <el-alert
        v-else-if="optionsLoaded && !configured"
        type="info"
        :closable="false"
        show-icon
        title="该维度还没有集成配置"
        description="填好 client_id 与 client_secret 这一维度的单点登录就能用；不填则不提供单点登录。填了密钥之后，下面各项都要填全。"
        class="body-alert"
      />

      <!--
        两块配置都做成可折叠（2026-11-25）：单点登录十几个框、消息推送五个框，
        平铺在一屏里要滚很久，而多数时候管理员只改其中一块。
        单点登录默认展开（改得最多），消息推送默认收起。

        原先这里还有一个「启用单点登录」开关，已删除：它和真实能力不挂钩 ——
        开着没填密钥、填好了却关着，两种状态都能存进去。「能不能用」现在只看配置：
        client_id 与 client_secret 都在就能用，缺一样这个维度就不提供单点登录。
      -->
      <el-collapse v-model="openedSections" class="section-collapse">
        <el-collapse-item name="sso">
          <template #title>
            <span class="collapse-title">单点登录</span>
            <!-- 用 ssoOn 而不是 ssoReady：用户刚填了新密钥还没保存时，标题不该还挂着「未配置」 -->
            <el-tag v-if="optionsLoaded && !ssoOn" size="small" type="info" class="collapse-tag">
              未配置
            </el-tag>
          </template>
          <el-form
            ref="ssoFormRef"
            :model="form"
            :rules="ssoRules"
            label-position="top"
          >
            <!--
              字段分四组（2026-11-25 重排）：协议与凭证 / 接口地址 / 接口参数 / 身份匹配与回调。

              原来是一个大平铺 + 一个「高级」折叠面板，问题是<b>必填项被收在折叠里</b>：
              根地址、回调票据参数名、access_token 字段名、两个请求方式都是必填，
              收起时看不见、点了保存才标红 —— 等于把必填项藏起来了。
              现在全部平铺可见，只按「填的时候心里想的是同一件事」分组。
            -->
            <div class="field-group">
              <div class="group-title">协议与凭证</div>
              <el-form-item label="协议类型" prop="ssoProviderType" class="span-2">
                <el-select
                  v-model="form.ssoProviderType"
                  class="full-width"
                  placeholder="请选择"
                >
                  <el-option
                    v-for="type in options.providerTypes"
                    :key="type"
                    :label="providerLabel(type)"
                    :value="type"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="client_id" prop="ssoClientId">
                <el-input v-model="form.ssoClientId" placeholder="对方系统分配的客户端 ID" />
              </el-form-item>
              <!--
                这里不能挂 prop / :required（2026-11-25 修）：密钥不走 form.ssoClientSecret，
                它是单独用 ssoSecretInput 管的（要区分「没改」「改了」「留空」三态，见下面的说明）。
                prop 指向一个 form 里不存在的字段，Element Plus 取到的永远是 undefined，
                于是不管填没填都报「ssoClientSecret is required」—— 表现是没配过密钥的维度
                无论怎么填都存不进去。语义上它本来也不是必填：不填＝这个维度不走单点登录。
              -->
              <el-form-item label="client_secret">
                <el-input
                  :model-value="ssoSecretDisplay"
                  :type="ssoSecretPlain ? 'text' : 'password'"
                  :placeholder="ssoSecretPlaceholder"
                  @update:model-value="onSsoSecretInput"
                >
                  <template #suffix>
                    <el-icon
                      v-if="ssoSecretConfigured"
                      class="secret-eye"
                      :title="ssoSecretPlain ? '隐藏' : '查看明文（会记入日志）'"
                      @click="toggleSsoSecret"
                    >
                      <Loading v-if="revealingSso" class="is-loading" />
                      <Hide v-else-if="ssoSecretPlain" />
                      <View v-else />
                    </el-icon>
                  </template>
                </el-input>
                <div class="form-hint">
                  本维度登录用的密钥，不填就不走单点登录。
                </div>
              </el-form-item>
            </div>

            <!--
              接口地址按「当前协议的形态」渲染，不写死几行：泛微是发起认证 + 票据兑换两个
              （用户信息地址是协议固定路径，不上页面），票据模式也是两个但兑换即得工号 ——
              写死「三个地址」的话，协议里没有的那个框既没有对应接口、又标着必填，
              用户只能编个假地址才能保存。

              2026-11-25 起每个地址都必须填完整 URL，不再有「留空由根地址推导」。
              每个地址占整行（span-2）：URL 长，半宽格子会把它截成两截，核对时看不清。
            -->
            <div class="field-group">
              <div class="group-title">接口地址</div>
              <el-form-item
                v-for="field in shape.fields"
                :key="field.key"
                :label="field.label"
                :prop="urlPropOf(field.key)"
                class="span-2"
                :rules="field.required && ssoOn
                  ? [{ required: true, message: `请填写${field.label}`, trigger: 'blur' }]
                  : []"
              >
                <el-input
                  :model-value="urlOf(field.key)"
                  :placeholder="urlExampleOf(field.key)"
                  @update:model-value="setUrl(field.key, $event)"
                />
                <div v-if="field.hint" class="form-hint">{{ field.hint }}</div>
              </el-form-item>
            </div>

            <!--
              接口参数：这些是「地址后面的问号串和响应里的字段名」，跟地址本身是两回事，
              所以单独一组。全部必填，全部平铺可见（原来是收在「高级」折叠里的）。
            -->
            <div class="field-group">
              <div class="group-title">接口参数</div>

              <el-form-item label="用户标识字段" prop="ssoUserinfoField">
                <el-input
                  v-model="form.ssoUserinfoField"
                  placeholder="对方返回身份值的字段名"
                />
              </el-form-item>

              <el-form-item label="回调票据参数名" prop="ssoCallbackParam">
                <el-input v-model="form.ssoCallbackParam" placeholder="如 ticket / code" />
              </el-form-item>

              <!-- access_token 是「票据→凭证→身份」三步协议才有的中间物；票据模式一次就拿到身份，没有这一步 -->
              <el-form-item
                v-if="shape.accessTokenStep"
                label="access_token 字段名"
                prop="ssoAccessTokenField"
              >
                <el-input
                  v-model="form.ssoAccessTokenField"
                  placeholder="如 access_token"
                />
              </el-form-item>

              <el-form-item label="兑换请求方式" prop="ssoTokenMethod">
                <el-select v-model="form.ssoTokenMethod" class="full-width" placeholder="请选择">
                  <el-option label="GET" value="GET" />
                  <el-option label="POST（表单提交）" value="POST" />
                </el-select>
              </el-form-item>

              <el-form-item
                v-if="shape.accessTokenStep"
                label="用户信息请求方式"
                prop="ssoProfileMethod"
              >
                <el-select v-model="form.ssoProfileMethod" class="full-width" placeholder="请选择">
                  <el-option label="GET" value="GET" />
                  <el-option label="POST（表单提交）" value="POST" />
                </el-select>
              </el-form-item>
            </div>

            <!--
              身份匹配与回调：这一组都是「拿到身份之后怎么用」。
              根地址只对本协议「有固定路径」时才有意义（拼注销地址、按根地址代填接口）；
              票据模式没有固定路径，这个框填了也推不出任何地址 —— 留着反而以为填了有用，所以不显示。
            -->
            <div class="field-group">
              <div class="group-title">身份匹配与回调</div>

              <!--
                身份匹配字段不预选（2026-11-25）：泛微必须 loginid（164 人登录名与工号不同），
                票据模式对方返回的是工号。这两个值配错的后果是那批人登不进来，
                所以必须由人明确选一次，而不是跟着协议默认走、也不是换协议时自动带默认。
              -->
              <el-form-item label="身份匹配字段" prop="ssoMatchField" class="span-2">
                <el-radio-group v-model="form.ssoMatchField">
                  <el-radio v-for="field in options.matchFields" :key="field" :value="field">
                    {{ matchFieldLabel(field) }}
                  </el-radio>
                </el-radio-group>
                <div class="form-hint">
                  {{ matchFieldHint }}
                </div>
              </el-form-item>

              <el-form-item
                v-if="shape.derivableFromBase"
                label="认证中心根地址"
                prop="ssoBaseUrl"
                class="span-2"
              >
                <el-input v-model="form.ssoBaseUrl" placeholder="如 http://oa.example.com:8080" />
                <div class="form-hint">
                  认证中心的站点根，用于拼注销地址。
                  <el-button v-if="canRederive" link type="primary" @click="rederiveUrls">
                    按根地址填上面的地址
                  </el-button>
                </div>
              </el-form-item>

              <!--
                回调地址只读且不可改：它必须和对方系统登记的白名单逐字符一致，改一边不改另一边
                就报「重定向地址不匹配」，该维度所有人当场登不进来。完整值由后端拼（站点根在
                服务端配置），页面上不给人改 —— 需要按维度分路径时由后端统一决定，
                见 DimensionIntegrationService.redirectUriOf。
              -->
              <el-form-item label="回调地址" class="span-2">
                <div class="callback-url">
                  <code v-if="callbackUrl">{{ callbackUrl }}</code>
                  <span v-else class="callback-url-empty">
                    站点根（assess.fill-base-url）未配置，拼不出完整地址
                  </span>
                  <el-button v-if="callbackUrl" link type="primary" @click="copyCallbackUrl">
                    复制
                  </el-button>
                  <el-button link type="primary" @click="usageVisible = true">怎么用</el-button>
                </div>
              </el-form-item>
            </div>
          </el-form>
        </el-collapse-item>

        <el-collapse-item name="push">
          <template #title>
            <span class="collapse-title">消息推送</span>
            <el-tag v-if="optionsLoaded && !form.pushEnabled" size="small" type="info" class="collapse-tag">
              已关闭
            </el-tag>
          </template>
          <el-form :model="form" label-position="top">
            <div class="form-grid">
              <el-form-item label="启用推送">
                <el-switch v-model="form.pushEnabled" />
                <div class="form-hint">
                  关闭后本维度不产生推送消息。
                </div>
              </el-form-item>
              <el-form-item label="推送渠道" required>
                <el-select v-model="form.pushChannel" class="full-width">
                  <el-option
                    v-for="channel in options.pushChannels"
                    :key="channel"
                    :label="channelLabel(channel)"
                    :value="channel"
                  />
                </el-select>
                <div class="form-hint">
                  目前只有 {{ implementedChannels }} 已实现消费端。
                </div>
              </el-form-item>
            </div>

            <div class="form-grid">
              <el-form-item label="渠道 agentId（可选）">
                <el-input v-model="form.pushAgentId" placeholder="留空用渠道默认" />
              </el-form-item>
              <el-form-item label="渠道密钥（可选）">
                <el-input
                  :model-value="pushSecretDisplay"
                  :type="pushSecretPlain ? 'text' : 'password'"
                  :placeholder="pushSecretPlaceholder"
                  @update:model-value="onPushSecretInput"
                >
                  <template #suffix>
                    <el-icon
                      v-if="pushSecretConfigured"
                      class="secret-eye"
                      :title="pushSecretPlain ? '隐藏' : '查看明文（会记入日志）'"
                      @click="togglePushSecret"
                    >
                      <Loading v-if="revealingPush" class="is-loading" />
                      <Hide v-else-if="pushSecretPlain" />
                      <View v-else />
                    </el-icon>
                  </template>
                </el-input>
                <div class="form-hint">
                  渠道推送用的密钥，留空则不带。
                </div>
              </el-form-item>
            </div>

            <el-form-item label="渠道扩展参数（JSON，可选）">
              <el-input
                v-model="form.pushExtraJson"
                type="textarea"
                :rows="3"
                placeholder='如 {"msgType": 596}'
              />
              <div class="form-hint">
                原样传给渠道消费端。
              </div>
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>

    <!--
      回调地址使用说明：这段文字要能照着做，所以按「给谁、填到哪、对不上会怎样、什么时候不用管」
      四段写，而不是罗列字段含义。用 append-to-body 嵌在配置弹窗里：不设的话内层弹窗会被
      外层 dialog 的 DOM 层级压住，出现「点了没反应」。
    -->
    <el-dialog
      v-model="usageVisible"
      title="回调地址怎么用"
      width="560px"
      append-to-body
    >
      <div class="usage">
        <p class="usage-lead">
          这个地址是<b>对方系统把人送回来时走的门</b>。你不需要改它，只需要把它交给对方登记。
        </p>

        <h4>一、给谁</h4>
        <p>
          给<b>提供单点登录的那一方</b>——泛微就是泛微的实施/运维，三方系统就是贵方的对接人。
          不给业务用户，也不填在我们系统里。
        </p>

        <h4>二、填到哪</h4>
        <p>
          填进对方<b>应用注册表里的「回调地址 / 重定向地址（redirect_uri）」</b>，
          与我们这边这个应用（client_id）对应那一条。
        </p>

        <h4>三、必须逐字符一致</h4>
        <p>对方是按字符串比对的，不做前缀匹配、不做通配。下面这些都会被判为不合法：</p>
        <ul>
          <li>末尾多一个斜杠：<code>…/sso/callback/</code></li>
          <li><code>http</code> 写成 <code>https</code>，或者反过来</li>
          <li>多了端口、多了 query 参数</li>
        </ul>
        <p class="usage-warn">
          对不上的表现：点登录后<b>根本跳不回来</b>。泛微会报
          <code>1005 参数 redirect_uri 非法（回调地址与注册信息不符）</code>，
          该维度的所有人都登不进来。
        </p>

        <h4>四、什么时候不用管</h4>
        <p>
          如果对方那边<b>早就登记过</b>这个地址（现在三个泛微维度就是这种情况），
          就不用再通知任何人，什么都不用做。
        </p>

        <h4>五、怎么验证</h4>
        <p>
          拿一个测试账号，从对方门户点「能力测评」。能直接进来就是对的；
          看到对方的错误页就是白名单没对上——把上面的完整地址原样发给对方核对。
        </p>
      </div>
      <template #footer>
        <el-button @click="usageVisible = false">知道了</el-button>
        <el-button type="primary" @click="copyCallbackUrl">复制地址</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Hide, Loading, View } from '@element-plus/icons-vue'
import {
  getDimensionIntegration,
  getIntegrationOptions,
  revealDimensionSecret,
  saveDimensionIntegration,
} from '@/api/assessDimension'
import {
  SECRET_MASK,
  type DimensionIntegrationSaveDTO,
  type IntegrationOptions,
  type ProviderOptionVO,
  type SsoShape,
} from '@/types/assessDimension'

const props = defineProps<{ dimensionId: number; dimensionName: string }>()
const visible = defineModel<boolean>({ required: true })

const loading = ref(false)
const saving = ref(false)
const optionsLoaded = ref(false)
const configured = ref(false)

const emptyOptions: IntegrationOptions = {
  providerTypes: [],
  matchFields: [],
  pushChannels: [],
  secretCipherReady: true,
}
const options = ref<IntegrationOptions>(emptyOptions)

/**
 * 表单初值一律为空 —— 包括协议类型与身份匹配字段。
 *
 * <p><b>为什么连这两个也不预选</b>（2026-11-25）：预选 weaver / loginid 之后，页面一打开
 * 就是一份「看起来配好了」的表单，用户很容易只补个 client_id 就保存，而他从没确认过
 * 对方到底走 ticket 还是 code、返回的是登录名还是工号。后两个配错的代价是那批人登不进来。
 * 空着逼人做一次明确的选择，成本是一次点击。</p>
 */
const form = reactive({
  ssoProviderType: '',
  ssoClientId: '',
  ssoBaseUrl: '',
  ssoAuthorizeUrl: '',
  ssoTokenUrl: '',
  ssoProfileUrl: '',
  ssoUserinfoField: '',
  ssoCallbackParam: '',
  ssoTokenMethod: '',
  ssoProfileMethod: '',
  ssoAccessTokenField: '',
  ssoMatchField: '',
  ssoRedirectPath: '',
  pushEnabled: true,
  pushChannel: 'weaver',
  pushAgentId: '',
  pushExtraJson: '',
})

/**
 * 折叠面板展开状态：单点登录默认展开（改得最多），消息推送默认收起。
 * 没配过时两块都展开 —— 新维度要一次填完，让人一眼看见还有哪些框。
 */
const openedSections = ref<string[]>(['sso'])

const ssoFormRef = ref<FormInstance>()

/**
 * 单点登录表单的校验规则。
 *
 * <p>逐项给规则而不是点保存时弹一条合并的 warning：配置页有十几个框，只报一句
 * 「请填全」的话用户得自己找是哪几项空着。逐项规则会在对应输入框下直接标红。
 * 后端还有一份同源的校验（`validateByProviderShape`），前端这份只是为了少一次失败往返 ——
 * 后面那一步才是权威，它按协议形态决定「本协议到底要哪几项」。</p>
 *
 * <p><b>必填范围跟「这一维度要不要用单点登录」走</b>（2026-11-25）：填了密钥才算要用，
 * 这时每一项都要填；没填密钥（＝这个维度不提供单点登录）时，只有协议类型、client_id、
 * 身份匹配字段这几项仍然必填 —— 它们是库里的非空列，也是以后要补配置时的落点。</p>
 */
const ssoRules = computed<FormRules>(() => {
  const required = (message: string, trigger: 'blur' | 'change' = 'blur') =>
    ssoOn.value ? [{ required: true, message, trigger }] : []
  const rules: FormRules = {
    ssoProviderType: [{ required: true, message: '请选择协议类型', trigger: 'change' }],
    ssoClientId: [{ required: true, message: '请填写 client_id', trigger: 'blur' }],
    ssoMatchField: [{ required: true, message: '请选择身份匹配字段', trigger: 'change' }],
    ssoUserinfoField: required('请填写用户标识字段'),
    ssoCallbackParam: required('请填写回调票据参数名'),
    ssoTokenMethod: required('请选择兑换请求方式', 'change'),
  }
  // 这两项只有「兑换后还要再取一次身份」的协议才有意义（票据模式没有这一步）
  if (ssoOn.value && shape.value.accessTokenStep) {
    rules.ssoProfileMethod = [{ required: true, message: '请选择用户信息请求方式', trigger: 'change' }]
    rules.ssoAccessTokenField = [{ required: true, message: '请填写 access_token 字段名', trigger: 'blur' }]
  }
  // 根地址只有「有固定路径」的协议才需要（拼注销地址）；票据模式没有认证中心这个概念
  if (ssoOn.value && shape.value.derivableFromBase) {
    rules.ssoBaseUrl = [{ required: true, message: '请填写认证中心根地址', trigger: 'blur' }]
  }
  return rules
})

/**
 * 当前协议在配置页上的形态（后端 SsoShape 原样回传）。
 *
 * <p><b>为什么页面不按协议名写死「泛微长这样、票据长那样」</b>：那样每接一个协议就要改一次页面，
 * 而且改漏了不会报错 —— 表现是「页面上有个必填框，但这个协议里根本没有这个接口」。
 * 形态由协议自己声明，页面只管照着渲染。</p>
 *
 * <p>拿不到形态时（老接口 / 加载失败）退化成显示发起认证与票据兑换两个地址，不会白屏。</p>
 */
const voShape = ref<SsoShape | null>(null)

const shape = computed<SsoShape>(() => voShape.value ?? {
  fields: [
    { key: 'authorizeUrl', label: '发起认证地址', required: true, hint: null },
    { key: 'tokenUrl', label: '票据兑换地址', required: true, hint: null },
  ],
  accessTokenStep: true,
  derivableFromBase: true,
})

/** 本协议各接口在根地址下的固定路径，只用于「按根地址填上面的地址框」 */
const providerPaths = ref({
  authorizePath: '',
  tokenPath: '',
  profilePath: '',
})

/** key → 表单字段，读写都走这里，避免在模板里写一堆 if */
const URL_KEY_MAP = {
  authorizeUrl: 'ssoAuthorizeUrl',
  tokenUrl: 'ssoTokenUrl',
  profileUrl: 'ssoProfileUrl',
} as const

const urlOf = (key: string): string => {
  const formKey = URL_KEY_MAP[key as keyof typeof URL_KEY_MAP]
  return formKey ? (form[formKey] as string) : ''
}

const setUrl = (key: string, value: string) => {
  const formKey = URL_KEY_MAP[key as keyof typeof URL_KEY_MAP]
  if (formKey) form[formKey] = value
}

/** key → el-form-item 的 prop（校验规则要挂在字段名上，不能挂在临时变量上） */
const urlPropOf = (key: string): string | undefined =>
  URL_KEY_MAP[key as keyof typeof URL_KEY_MAP]

/** key → 该协议在这个接口上的固定路径。空串表示本协议推不出来，必须手填完整地址 */
const PATH_OF: Record<string, () => string> = {
  authorizeUrl: () => providerPaths.value.authorizePath,
  tokenUrl: () => providerPaths.value.tokenPath,
  profileUrl: () => providerPaths.value.profilePath,
}

/** key → 填写示例。示例按字段给：三个框共用一个的话「票据兑换地址」里会摆个 …/authorize */
const URL_EXAMPLE: Record<string, string> = {
  authorizeUrl: 'https://open.example.com/api/sso/authorize',
  tokenUrl: 'https://open.example.com/api/sso/token',
  profileUrl: 'https://open.example.com/api/sso/profile',
}

/**
 * 占位提示：只说「填完整 URL、长什么样」。
 *
 * <p>以前这里写的是「留空即自动拼出（/sso/oauth2.0/authorize）」—— 那句话在当时的机制下
 * 是对的，但它的副作用是用户看到 placeholder 就以为这栏有值、一路点保存，
 * 库里那一栏一直是空的。现在不再提供「留空也能用」这种可能，提示也就只给格式示例。</p>
 */
const urlExampleOf = (key: string): string =>
  `完整 URL，如 ${URL_EXAMPLE[key] ?? 'https://open.example.com/api/sso/…'}`

/** 实际会用的完整回调地址，由后端拼好（与真正发起登录时同一份逻辑） */
const callbackUrl = ref<string | null>(null)

/** 「怎么用」说明弹窗 */
const usageVisible = ref(false)

const copyCallbackUrl = async () => {
  if (!callbackUrl.value) return
  try {
    await navigator.clipboard.writeText(callbackUrl.value)
    ElMessage.success('已复制完整回调地址')
  } catch {
    // 非 https / 浏览器拒绝剪贴板权限时退化成选中文本，让用户自己复制
    ElMessage.warning('浏览器不允许自动复制，请手动选中地址复制')
  }
}

/** 根地址在、且本协议三个接口都有固定路径，才谈得上「按根地址填」 */
const canRederive = computed(() => {
  if (!form.ssoBaseUrl.trim() || !shape.value.derivableFromBase) return false
  return shape.value.fields.every((f) => !!PATH_OF[f.key]?.())
})

/**
 * 用根地址把本协议用到的地址填进输入框。
 *
 * <p>这只是个省手工的按钮，<b>不是</b>「留空由服务端推导」：按下去值是真的写进输入框，
 * 用户看得见、能改、保存时原样落库。所以不自动跟随根地址变化 ——
 * 一改根地址就覆盖用户手改过的某个地址，会把「网关另一侧的接口」这种例外冲掉。</p>
 */
const rederiveUrls = () => {
  const base = form.ssoBaseUrl.trim().replace(/\/+$/, '')
  for (const field of shape.value.fields) {
    const path = PATH_OF[field.key]?.()
    if (path) setUrl(field.key, base + path)
  }
  ElMessage.success(`已按根地址填好 ${shape.value.fields.length} 个地址，保存后生效`)
}

/**
 * 密钥输入与库里的密钥分开管。
 *
 * <p><b>为什么要分这几层</b>：页面上看到的、用户改过的、库里存的，是几个不同的东西。</p>
 * <ul>
 *   <li>{@code *SecretInput} —— 真正会被提交的值。用户没敲过键盘时它不参与提交。</li>
 *   <li>{@code *SecretPlain} —— 输入框当前是明文（眼睛睁开）还是掩码（眼睛闭上）。</li>
 *   <li>{@code *SecretDirty} —— 用户是否真的敲过键盘。看眼睛不算改：只是把明文拉下来看了一眼就保存，
 *       不应该把密文重算一遍（那样密文天天变，出问题没法比对）。</li>
 * </ul>
 * 明文只有在第一次点眼睛时才向服务端取，且不缓存到下次打开。
 */
const ssoSecretInput = ref('')
const pushSecretInput = ref('')
/** 输入框当前显示的是明文（true）还是掩码（false） */
const ssoSecretPlain = ref(false)
const pushSecretPlain = ref(false)
/** 用户是否改过内容（只有敲键盘才算） */
const ssoSecretDirty = ref(false)
const pushSecretDirty = ref(false)
/** 明文是否已经取回来了（眼睛第二次点击起只在本地切换，不重复请求也不重复留痕） */
const ssoSecretFetched = ref(false)
const pushSecretFetched = ref(false)
const ssoSecretConfigured = ref(false)
const pushSecretConfigured = ref(false)
const revealingSso = ref(false)
const revealingPush = ref(false)

/** 掩码长度固定 12 个点：真实长度也属于「密钥信息」，不透露 */
const SECRET_DOTS = '••••••••••••'

/**
 * 这个维度现在是不是提供单点登录。
 *
 * <p>判据只有一条：有没有 client_secret。没有密钥就换不了 token，也就谈不上登录；
 * 原来那个「启用单点登录」开关不参与判断（已删除，见模板顶部的注释）。</p>
 */
const ssoReady = computed(() => ssoSecretConfigured.value)

/**
 * 这次保存后单点登录会不会生效：库里已有密钥，或者用户这次填了新的。
 *
 * <p>它同时决定「其余各项要不要标必填」—— 不用单点登录的维度不该被一堆必填框堵住。
 * 判据与后端 {@code save()} 里那条「密钥为空就跳过必填校验」是同一件事，
 * 两边都从密钥出发，不会出现一边要求填、一边又放过。</p>
 *
 * <p><b>用户把密钥删掉这一态必须单独算</b>：{@code ssoSecretConfigured} 说的是「库里有没有」，
 * 它是打开弹窗时的快照，不跟着输入变。只看它的话，把库里有密钥的维度清空后，
 * 其余各项仍然标着必填、一保存就被红框拦住 —— 而这次改动的意图恰恰是停用，
 * 与后端的放行规则相反。所以这里把「改过且清空」也算作关。</p>
 */
const ssoOn = computed(() => {
  const typed = ssoSecretInput.value.trim()
  if (typed) return true
  // 用户删掉了：这次保存写库的是 NULL，后端不再校验其余项
  if (ssoSecretDirty.value) return false
  return ssoReady.value
})

/** 已配置且眼睛闭着 → 显示掩码；睁眼显示明文；用户改过就显示他输入的内容 */
const ssoSecretDisplay = computed(() => {
  if (!ssoSecretPlain.value) return ssoSecretConfigured.value ? SECRET_DOTS : ''
  return ssoSecretInput.value
})

const pushSecretDisplay = computed(() => {
  if (!pushSecretPlain.value) return pushSecretConfigured.value ? SECRET_DOTS : ''
  return pushSecretInput.value
})

/**
 * 用户敲键时把掩码收掉。
 *
 * <p>掩码是<b>真实填进输入框的 12 个点字符</b>（不是占位符），所以用户点在中间直接敲时，
 * 浏览器会把新字符插进那串点里 —— 提交上去就是「点+新字符」，看着像密钥其实不是。
 * 这里把值里出现的整段掩码剥掉，剩下的才是用户这次真正输入的内容。</p>
 *
 * <p>能收到这个事件的唯一前提是用户动了键盘（或粘贴），所以这里顺手把 dirty 置上；
 * 点眼睛走的是 {@link toggleSsoSecret}，不经过这里，dirty 不会被误置。</p>
 */
const stripMask = (value: string): string => value.split(SECRET_DOTS).join('')

const onSsoSecretInput = (value: string) => {
  ssoSecretDirty.value = true
  ssoSecretPlain.value = true
  ssoSecretInput.value = stripMask(value)
}

const onPushSecretInput = (value: string) => {
  pushSecretDirty.value = true
  pushSecretPlain.value = true
  pushSecretInput.value = stripMask(value)
}

/**
 * 眼睛：掩码 ↔ 明文。
 *
 * <p>第一次睁开时才向服务端取一次明文（每次取都留痕），之后本地来回切，不重复请求。
 * 取失败就保持闭眼，不给用户一个空框当「明文」。</p>
 */
const toggleSsoSecret = async () => {
  if (ssoSecretPlain.value) {
    ssoSecretPlain.value = false
    return
  }
  if (!ssoSecretFetched.value) {
    revealingSso.value = true
    try {
      ssoSecretInput.value = await revealDimensionSecret(props.dimensionId, 'sso_client_secret')
      ssoSecretFetched.value = true
    } catch (error) {
      console.error('查看密钥失败:', error)
      return
    } finally {
      revealingSso.value = false
    }
  }
  ssoSecretPlain.value = true
}

const togglePushSecret = async () => {
  if (pushSecretPlain.value) {
    pushSecretPlain.value = false
    return
  }
  if (!pushSecretFetched.value) {
    revealingPush.value = true
    try {
      pushSecretInput.value = await revealDimensionSecret(props.dimensionId, 'push_secret')
      pushSecretFetched.value = true
    } catch (error) {
      console.error('查看密钥失败:', error)
      return
    } finally {
      revealingPush.value = false
    }
  }
  pushSecretPlain.value = true
}

const secretCipherReady = computed(() => options.value.secretCipherReady)

// 不填也能保存（＝这个维度不提供单点登录），所以不写「必填」—— 它没有必填星号，
// 文案说必填会让人以为自己被拦住了
const ssoSecretPlaceholder = computed(() =>
  ssoSecretConfigured.value ? '已保存（点右侧眼睛显示，直接输入则覆盖）' : '填写后本维度启用单点登录'
)
const pushSecretPlaceholder = computed(() =>
  pushSecretConfigured.value ? '已保存（点右侧眼睛显示，直接输入则覆盖）' : '未设置'
)

const implementedChannels = 'weaver'

const matchFieldHint = computed(() => {
  if (!form.ssoMatchField) {
    return '对方返回的身份值去人员表哪一列匹配。'
  }
  return form.ssoMatchField === 'workcode'
    ? '按工号匹配，可覆盖没有 OA 账号的人。'
    : '按 OA 登录名匹配，只覆盖有 OA 账号的人。'
})

const providerLabel = (type: string) => {
  const map: Record<string, string> = {
    weaver: 'weaver（泛微 e-cology）',
    ticket: 'ticket（票据模式，三方系统）',
    oauth2: 'oauth2（标准 OAuth2 / OIDC）',
  }
  // 没登记过的类型直接显示原值：后端加了新协议而这里忘了补，至少还能选到、不会显示成空白
  return map[type] ?? type
}

const channelLabel = (channel: string) => {
  const map: Record<string, string> = {
    weaver: 'weaver（OA 消息）',
    wecom: 'wecom（企业微信）',
    dingtalk: 'dingtalk（钉钉）',
    email: 'email（邮件）',
    sms: 'sms（短信）',
  }
  return map[channel] ?? channel
}

const matchFieldLabel = (field: string) =>
  field === 'workcode' ? '工号（workcode）' : 'OA 登录名（loginid）'

const handleOpen = async () => {
  loading.value = true
  ssoSecretInput.value = ''
  pushSecretInput.value = ''
  ssoSecretPlain.value = false
  pushSecretPlain.value = false
  ssoSecretDirty.value = false
  pushSecretDirty.value = false
  ssoSecretFetched.value = false
  pushSecretFetched.value = false
  try {
    const [vo, opts] = await Promise.all([
      getDimensionIntegration(props.dimensionId),
      getIntegrationOptions(),
    ])
    options.value = opts
    optionsLoaded.value = true
    configured.value = vo.configured
    // 还没配过的维度两块都展开：一次填完，不用先点开第二个折叠项才知道还有推送要配
    openedSections.value = vo.configured ? ['sso'] : ['sso', 'push']
    // 协议形态优先用「该维度当前协议」的（与库里一致）；options 里那份是给切换协议用的
    const option = opts.providerOptions?.[vo.ssoProviderType || '']
    if (option) {
      applyProviderOption(option)
    } else {
      // 回退：老接口没有 providerOptions 时仍用 VO 上的形态与固定路径字段
      providerPaths.value = {
        authorizePath: vo.defaultAuthorizePath || '',
        tokenPath: vo.defaultTokenPath || '',
        profilePath: vo.defaultProfilePath || '',
      }
      voShape.value = vo.ssoShape ?? null
    }
    // 完整回调地址随维度走，与协议无关
    callbackUrl.value = vo.callbackUrl ?? null
    // 已配置 → 眼睛闭着显示掩码：空框会让人以为「没有密钥」，从而误填一个覆盖掉正确的
    ssoSecretConfigured.value = !!vo.ssoClientSecret
    pushSecretConfigured.value = !!vo.pushSecret
    // 库里没配的项就是空字符串 —— 不用协议默认值填进来，让「这一栏确实没配」看得见
    Object.assign(form, {
      ssoProviderType: vo.ssoProviderType || '',
      ssoClientId: vo.ssoClientId || '',
      ssoBaseUrl: vo.ssoBaseUrl || '',
      ssoAuthorizeUrl: vo.ssoAuthorizeUrl || '',
      ssoTokenUrl: vo.ssoTokenUrl || '',
      ssoProfileUrl: vo.ssoProfileUrl || '',
      ssoUserinfoField: vo.ssoUserinfoField || '',
      ssoCallbackParam: vo.ssoCallbackParam || '',
      ssoTokenMethod: vo.ssoTokenMethod || '',
      ssoProfileMethod: vo.ssoProfileMethod || '',
      ssoAccessTokenField: vo.ssoAccessTokenField || '',
      ssoMatchField: vo.ssoMatchField || '',
      ssoRedirectPath: vo.ssoRedirectPath || '',
      pushEnabled: vo.pushEnabled ?? true,
      pushChannel: vo.pushChannel || 'weaver',
      pushAgentId: vo.pushAgentId || '',
      pushExtraJson: vo.pushExtraJson || '',
    })
    // 回填完再清一次校验红框：否则上次留下的错误标记会挂在新数据上
    ssoFormRef.value?.clearValidate()
  } catch (error) {
    console.error('加载集成配置失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 切换协议后把页面切成新协议的样子（显示哪几个地址框）。
 *
 * <p><b>不能用「重新读一次该维度的配置」</b>：那读回来的是<b>库里存的</b>协议，
 * 而用户刚在下拉框里选的那个还没保存 —— 两者恰好是要区分的东西。
 * 每个协议的形态已经由 {@code GET /integration-options} 一次全量给过来了，直接从那里取。</p>
 *
 * <p><b>不再跟着换匹配字段/身份字段</b>（2026-11-25）：原来换协议会自动把
 * 「身份匹配字段」带成新协议的默认值（泛微 loginid → 票据 workcode）。
 * 那个自动填充的副作用是用户根本没注意到它变过，而这个值配错的代价是整批人登不进来。
 * 现在换协议只改「显示哪些框」，值一律要人自己填/自己选。</p>
 */
const reloadProviderShape = (type: string) => {
  const option = options.value.providerOptions?.[type]
  if (!option) return
  applyProviderOption(option)
}

/** 把一个协议的描述装进本地（打开弹窗与切换协议共用） */
const applyProviderOption = (option: ProviderOptionVO) => {
  providerPaths.value = {
    authorizePath: option.defaultAuthorizePath || '',
    tokenPath: option.defaultTokenPath || '',
    profilePath: option.defaultProfilePath || '',
  }
  voShape.value = option.shape
}

watch(visible, (open) => {
  if (open) {
    void handleOpen()
  }
}, { immediate: true })

/**
 * 换协议时把「哪些地址框显示」跟着换。
 *
 * <p>只在用户真的改了协议选择时触发（不是打开弹窗时）：打开时后端已经把库里的值回填好了，
 * 在这里再刷一遍会把人家的配置覆盖掉。</p>
 */
watch(() => form.ssoProviderType, (type, oldType) => {
  if (!type || !oldType || type === oldType) return
  reloadProviderShape(type)
})

/**
 * 三种语义收敛成要发给后端的一个值。
 *
 * <p>{@code dirty} 为 false 时一律回占位符：点眼睛只是「看了明文一眼」，没改内容，
 * 不该让后端重新加密一遍（密文一变，前后就没法比对是不是同一个密钥）。</p>
 *
 * <p>用户把框清空是<b>独立的一种改动</b>，返回空串（后端语义＝清空）；
 * 但真正提交前要先过一次确认，见 {@link confirmSecretClear}。</p>
 */
const resolveOutgoingSecret = (input: string, dirty: boolean): string => {
  if (!dirty) return SECRET_MASK
  return input.trim()
}

/** 哪些密钥这次会被删掉：改过、清空、且库里原本有值 */
const pendingClears = () => {
  const fields: { label: string; value: boolean }[] = []
  if (ssoSecretConfigured.value && ssoSecretDirty.value && ssoSecretInput.value.trim() === '') {
    fields.push({ label: '单点登录 client_secret', value: true })
  }
  if (pushSecretConfigured.value && pushSecretDirty.value && pushSecretInput.value.trim() === '') {
    fields.push({ label: '推送渠道密钥', value: true })
  }
  return fields
}

/**
 * 清空密钥不可逆（删掉之后只剩页面上还开着的明文可看），提交前问一次。
 *
 * <p>放在点保存时问，而不是输入框失焦时问：点保存按钮本身就是先失焦再点击，
 * 两件事会同时发生 —— 确认框和保存请求会打架。钉在提交这一刻，语义才唯一。</p>
 */
const confirmSecretClear = async (labels: string[]): Promise<boolean> => {
  try {
    await ElMessageBox.confirm(
      `保存后将删除已保存的：${labels.join('、')}。删除后无法还原，只能重新填写。`,
      '确认删除密钥',
      { type: 'warning', confirmButtonText: '删除并保存', cancelButtonText: '取消' }
    )
    return true
  } catch {
    return false
  }
}

/**
 * 提交前的整体校验。
 *
 * <p>单点登录部分靠 {@link ssoRules} 逐项标红（{@code validate()} 一次把所有缺项都标出来）；
 * 这里只补那些不适合挂在单个输入框上的检查：JSON 不合法、推送渠道。</p>
 *
 * <p><b>密钥被清空不再是错误</b>（2026-11-25）：清空 client_secret 就是「这个维度不用单点登录」，
 * 一个合法的状态。要确认的是「别把最后一个可用维度也清掉」—— 那个判断在服务端做，
 * 因为它要知道其它维度的情况，前端看不见。</p>
 */
const validateForm = async (): Promise<string | null> => {
  if (!form.pushChannel) {
    return '请选择推送渠道'
  }
  const extra = form.pushExtraJson.trim()
  if (extra) {
    try {
      JSON.parse(extra)
    } catch {
      return '渠道扩展参数不是合法 JSON'
    }
  }
  if (ssoFormRef.value) {
    try {
      await ssoFormRef.value.validate()
    } catch {
      // validate() 失败时 ElForm 已经在对应字段下标红并滚动到了第一个错误项，
      // 这里只需给一句总括提示，不重复列字段名（列表可能很长，横排会换行难看）
      return '单点登录还有必填项没填完，请看标红的输入框'
    }
  }
  return null
}

const handleSave = async () => {
  const invalid = await validateForm()
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  const clears = pendingClears()
  if (clears.length > 0 && !(await confirmSecretClear(clears.map((c) => c.label)))) {
    return
  }
  saving.value = true
  try {
    // 所有字段原样提交，<b>不按当前协议裁剪</b>：换协议时隐藏的框里留的是上一个配置的值，
    // 看着「对本协议没用」，但删掉它们就是真删库里的数据 —— 用户从泛微切到票据模式看一眼
    // 再切回来，地址全没了。多留几个本协议不读的值不会造成任何行为差异
    // （票据模式压根不读 profileUrl / baseUrl），而丢数据不可逆。
    //
    // 用户信息地址是个例外：它已经不在页面上，服务端按协议形态忽略这个入参、写回库里原值
    // （见 DimensionIntegrationController.save），所以这里继续照着表单里的旧值提交，
    // 对结果没有影响 —— 留着只是让「整份表单原样提交」这条规则不需要记住例外。
    const payload: DimensionIntegrationSaveDTO = {
      dimensionId: props.dimensionId,
      ssoProviderType: form.ssoProviderType,
      ssoClientId: form.ssoClientId.trim(),
      ssoClientSecret: resolveOutgoingSecret(ssoSecretInput.value, ssoSecretDirty.value),
      ssoBaseUrl: form.ssoBaseUrl.trim() || undefined,
      ssoAuthorizeUrl: form.ssoAuthorizeUrl.trim() || undefined,
      ssoTokenUrl: form.ssoTokenUrl.trim() || undefined,
      ssoProfileUrl: form.ssoProfileUrl.trim() || undefined,
      ssoUserinfoField: form.ssoUserinfoField.trim() || undefined,
      ssoCallbackParam: form.ssoCallbackParam.trim() || undefined,
      ssoTokenMethod: form.ssoTokenMethod || undefined,
      ssoProfileMethod: form.ssoProfileMethod || undefined,
      ssoAccessTokenField: form.ssoAccessTokenField.trim() || undefined,
      ssoMatchField: form.ssoMatchField,
      // 不传 ssoRedirectPath：回调路径在页面上是只读的，后端也忽略这个字段的入参，
      // 库里原有的值由后端原样写回（见 DimensionIntegrationController.save）。
      pushEnabled: form.pushEnabled,
      pushChannel: form.pushChannel,
      pushAgentId: form.pushAgentId.trim() || undefined,
      pushSecret: resolveOutgoingSecret(pushSecretInput.value, pushSecretDirty.value),
      pushExtraJson: form.pushExtraJson.trim() || undefined,
    }
    await saveDimensionIntegration(props.dimensionId, payload)
    ElMessage.success('集成配置已保存')
    visible.value = false
  } catch (error) {
    console.error('保存集成配置失败:', error)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.integration-body {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 4px;
}

.body-alert {
  margin-bottom: 16px;
}

/* 外层两块配置的折叠：去掉默认的上边距，标题按小节标题的样子排 */
.section-collapse {
  border-top: none;
}

.section-collapse :deep(.el-collapse-item__header) {
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  border-bottom: 1px solid #e5e6eb;
}

.section-collapse :deep(.el-collapse-item__content) {
  padding: 16px 0 4px;
}

.collapse-title {
  margin-right: 8px;
}

/* 标题右侧的状态标签：一眼看出这块配没配、开没开 */
.collapse-tag {
  vertical-align: middle;
}

/*
 * 分组：每组是一块独立的语义单元（凭证 / 地址 / 参数 / 匹配与回调）。
 * 半宽两列，只有一两个输入框的组不会拉成整行宽度；URL 这类长的用 .span-2 占满。
 */
.field-group {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
  padding: 12px 14px 2px;
  margin-bottom: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

/* 组标题占满整行，跟下面两个格子区分开 */
.group-title {
  grid-column: 1 / -1;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
}

/* 整行：长地址、单选组、只读回调地址都放整行，半宽放不下 */
.span-2 {
  grid-column: 1 / -1;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-hint {
  font-size: 12px;
  color: #86909c;
  line-height: 1.6;
  margin-top: 6px;
}

/* 眼睛跟 Element 自带 show-password 的外观对齐：次要色、可点、悬停变主色 */
.secret-eye {
  cursor: pointer;
  color: var(--el-text-color-placeholder);
  font-size: 14px;
}

.secret-eye:hover {
  color: var(--el-color-primary);
}

/* 完整回调地址：等宽显示，方便逐字符核对（对方要的是精确值） */
.callback-url {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.callback-url code {
  padding: 2px 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 13px;
  word-break: break-all;
}

.callback-url-empty {
  font-size: 12px;
  color: var(--el-color-warning);
}

.full-width {
  width: 100%;
}

/* 「怎么用」说明弹窗的排版：段落之间留白，重点句加重 */
.usage h4 {
  margin: 16px 0 6px;
  font-size: 13px;
  color: #1d2129;
}

.usage p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #4e5969;
}

.usage ul {
  margin: 6px 0;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
  color: #4e5969;
}

.usage-lead {
  color: #1d2129 !important;
}

.usage-warn {
  margin-top: 8px !important;
  color: #d4380d !important;
}
</style>
