package org.moontechlab.selenetv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthHelperRedirectTest {

    @Test fun follows_http_to_https_upgrade() {
        val t = AuthHelper.resolveLoginRedirect("http://host/api/login", "https://host/api/login")
        assertEquals("https://host", t?.baseUrl)
        assertEquals("https://host/api/login", t?.loginUrl)
    }

    @Test fun follows_trailing_slash_redirect_preserving_slash_in_login_url() {
        val t = AuthHelper.resolveLoginRedirect("https://host/api/login", "https://host/api/login/")
        assertEquals("https://host", t?.baseUrl)
        assertEquals("https://host/api/login/", t?.loginUrl)
    }

    @Test fun follows_relative_location() {
        val t = AuthHelper.resolveLoginRedirect("http://host/api/login", "/api/login")
        assertEquals("http://host", t?.baseUrl)
        assertEquals("http://host/api/login", t?.loginUrl)
    }

    @Test fun rejects_cross_host_redirect() {
        // 安全：跨 host 重定向会把用户名/密码 POST 到攻击者的服务器并永久篡改 baseUrl，必须拒绝
        val t = AuthHelper.resolveLoginRedirect("http://old.example/api/login", "https://new.example/api/login")
        assertNull(t)
    }

    @Test fun rejects_https_to_http_downgrade() {
        // 安全：HTTPS 已经成功握手的连接被引向明文 HTTP，凭据会被明文重发
        val t = AuthHelper.resolveLoginRedirect("https://host/api/login", "http://host/api/login")
        assertNull(t)
    }

    @Test fun allows_implicit_port_change_during_https_upgrade() {
        // host 相同时允许端口变化：HTTP(80) → HTTPS(443) 是合法的常见升级
        val t = AuthHelper.resolveLoginRedirect("http://host/api/login", "https://host/api/login")
        assertEquals("https://host", t?.baseUrl)
        assertEquals("https://host/api/login", t?.loginUrl)
    }

    @Test fun rejects_subdomain_redirect() {
        // 子域也算跨 host —— 子域很可能是攻击者控制的接管点
        val t = AuthHelper.resolveLoginRedirect("https://example.com/api/login", "https://login.example.com/api/login")
        assertNull(t)
    }

    @Test fun follows_subpath_baseurl_preserved() {
        // 服务部署在子路径下：例如 /lunatv/api/login → baseUrl = scheme://host/lunatv
        val t = AuthHelper.resolveLoginRedirect("http://host/lunatv/api/login", "https://host/lunatv/api/login")
        assertEquals("https://host/lunatv", t?.baseUrl)
        assertEquals("https://host/lunatv/api/login", t?.loginUrl)
    }

    @Test fun rejects_ftp_scheme() {
        val t = AuthHelper.resolveLoginRedirect("https://host/api/login", "ftp://host/api/login")
        assertNull(t)
    }

    @Test fun returns_null_on_missing_location() {
        assertNull(AuthHelper.resolveLoginRedirect("https://host/api/login", null))
    }

    @Test fun returns_null_on_blank_location() {
        assertNull(AuthHelper.resolveLoginRedirect("https://host/api/login", "   "))
    }

    @Test fun unexpected_path_falls_back_to_authority_root_baseurl() {
        val t = AuthHelper.resolveLoginRedirect("https://host/api/login", "/auth/sign-in")
        assertEquals("https://host", t?.baseUrl)
        assertEquals("https://host/auth/sign-in", t?.loginUrl)
    }
}
