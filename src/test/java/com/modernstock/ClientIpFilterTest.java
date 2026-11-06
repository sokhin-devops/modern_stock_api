package com.modernstock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.modernstock.config.ClientIpFilter;

/**
 * Behind Cloudflare the rate limits must count the visitor, not the proxy, and must not trust X-Forwarded-For, whose
 * first entry the visitor can write (DevSecOps.md 6.4).
 */
class ClientIpFilterTest {

    @Test
    void behindCloudflareTheVisitorIsCfConnectingIp() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");

        assertThat(remoteAddrSeenByTheApp(new ClientIpFilter("CF-Connecting-IP"), request)).isEqualTo("203.0.113.7");
    }

    @Test
    void withoutTheHeaderItIsTheConnectionAsBefore() throws Exception {
        assertThat(remoteAddrSeenByTheApp(new ClientIpFilter("CF-Connecting-IP"), request())).isEqualTo("10.0.0.5");
    }

    @Test
    void offByDefaultSoTheHeaderCantBeFakedOnAPc() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");

        assertThat(remoteAddrSeenByTheApp(new ClientIpFilter(""), request)).isEqualTo("10.0.0.5");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr("10.0.0.5");
        return request;
    }

    private static String remoteAddrSeenByTheApp(ClientIpFilter filter, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest().getRemoteAddr();
    }
}
