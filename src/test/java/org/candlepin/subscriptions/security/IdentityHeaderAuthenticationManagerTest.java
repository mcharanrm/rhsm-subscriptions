/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

@SpringBootTest
@TestPropertySource("classpath:/test.properties")
class IdentityHeaderAuthenticationManagerTest {

    String HEADER_JSON = "{\"identity\":{\"account_number\":\"acct\",\"internal\":{\"org_id\":\"123\"}}}";

    @MockBean
    PreAuthenticatedGrantedAuthoritiesWebAuthenticationDetails details;

    @Test
    void testOrgIdExtractedFromHeader() {
        IdentityHeaderAuthenticationManager manager =
            new IdentityHeaderAuthenticationManager(new ObjectMapper());

        PreAuthenticatedAuthenticationToken authentication = new PreAuthenticatedAuthenticationToken(
            HEADER_JSON.getBytes(StandardCharsets.UTF_8), "N/A");
        authentication.setDetails(details);

        Authentication result = manager.authenticate(authentication);

        InsightsUserPrincipal principal = new InsightsUserPrincipal("123", "acct");

        PreAuthenticatedAuthenticationToken expected = new PreAuthenticatedAuthenticationToken(principal,
            "N/A",
            Collections.emptyList());
        assertEquals(expected, result);
    }
}