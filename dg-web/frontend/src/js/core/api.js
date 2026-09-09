/** 统一 API 客户端：CSRF 注入、401 统一拦截跳转登录、错误信息提取。 */

const DEFAULT_BASE = '/api/v1';
const LOGIN_PATH = '/login.html';

/**
 * 强制整页跳转到登录页（避免 fetch 跟随 302 时只拉到 HTML、地址栏不变）。
 */
export function redirectToLogin() {
    if (window.location.pathname.endsWith(LOGIN_PATH)) {
        return;
    }
    window.location.assign(LOGIN_PATH);
}

/** 判断响应是否表示需要重新登录 */
export function requiresLogin(response) {
    if (response.status === 401) {
        return true;
    }
    // 后端若对未认证请求 302 到登录页，fetch 默认会跟随，最终 status=200 且 url 指向登录页
    return response.redirected && response.url.includes(LOGIN_PATH);
}

/**
 * 带登录态的 fetch：统一 credentials、写操作 CSRF、401/登录重定向拦截。
 *
 * @param url 完整请求 URL
 * @param options fetch 选项；额外支持 csrf=false 关闭 CSRF 注入
 * @returns {Promise<Response|null>} 需登录时已跳转并返回 null
 */
export async function fetchAuth(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { ...(options.headers || {}) };
    const { headers: _ignoredHeaders, csrf, ...restOptions } = options;

    if (csrf !== false
            && method !== 'GET'
            && method !== 'HEAD'
            && method !== 'OPTIONS'
            && typeof getCsrfToken === 'function') {
        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
    }

    const response = await fetch(url, {
        credentials: 'same-origin',
        ...restOptions,
        headers
    });

    if (requiresLogin(response)) {
        redirectToLogin();
        return null;
    }
    return response;
}

/**
 * 发起 JSON API 请求。
 *
 * @param path 接口路径（相对 base）
 * @param options fetch 选项（headers 与内置头合并）
 * @param base 基础路径，默认 /api/v1（dg-ai 代理等可传入 /api/v1/agent）
 * @returns 204 返回 null；需登录时跳转登录页后返回 null；其余返回解析后的 JSON
 */
export async function api(path, options = {}, base = DEFAULT_BASE) {
    const headers = { ...(options.headers || {}), 'Content-Type': 'application/json' };
    const response = await fetchAuth(`${base}${path}`, { ...options, headers });
    if (!response) {
        return null;
    }
    if (!response.ok) {
        let message = response.statusText;
        try {
            const body = await response.json();
            message = body.message || body.error || message;
        } catch (_) { /* ignore */ }
        throw new Error(message);
    }
    if (response.status === 204) {
        return null;
    }
    return response.json();
}
