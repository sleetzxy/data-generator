/** 统一 API 客户端：CSRF 注入、401 跳转登录、错误信息提取。 */

const DEFAULT_BASE = '/api/v1';

/**
 * 发起 JSON API 请求。
 *
 * @param path 接口路径（相对 base）
 * @param options fetch 选项（headers 与内置头合并，不会覆盖 Content-Type 与 CSRF Token）
 * @param base 基础路径，默认 /api/v1（dg-ai 代理等可传入 /api/v1/agent）
 * @returns 204 返回 null；401 跳转登录页后返回 null；其余返回解析后的 JSON
 */
export async function api(path, options = {}, base = DEFAULT_BASE) {
    const method = (options.method || 'GET').toUpperCase();
    // 内置头在调用方 headers 之后设置，确保 Content-Type 和 CSRF Token 不被覆盖
    const headers = { ...(options.headers || {}), 'Content-Type': 'application/json' };
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS' && typeof getCsrfToken === 'function') {
        const csrfToken = getCsrfToken();
        if (csrfToken) {
            headers['X-XSRF-TOKEN'] = csrfToken;
        }
    }

    // 排除 headers 避免重复展开覆盖内置头
    const { headers: _, ...restOptions } = options;
    const response = await fetch(`${base}${path}`, {
        credentials: 'same-origin',
        headers,
        ...restOptions
    });

    if (response.status === 401) {
        window.location.href = '/login.html';
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
