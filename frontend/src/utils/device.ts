/**
 * 移动端判定。
 *
 * 只用来决定「走 PC 版还是 H5 版」这一件事，不参与任何权限或业务判断，
 * 所以正则宁可宽一点：把平板也算作移动端（H5 版在平板上同样能看，
 * PC 版在平板上反而不便操作）。识别失败时按桌面处理，不会把 PC 用户赶去 H5。
 */
const MOBILE_UA = /Android|iPhone|iPad|iPod|Windows Phone|HarmonyOS|MicroMessenger|wxwork|Mobi|Tablet/i

export const isMobileUa = (ua: string = navigator.userAgent): boolean => MOBILE_UA.test(ua)
