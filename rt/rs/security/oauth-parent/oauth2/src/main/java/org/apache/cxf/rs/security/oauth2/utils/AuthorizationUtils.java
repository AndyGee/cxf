/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.rs.security.oauth2.utils;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.jaxrs.ext.MessageContext;

/**
 * Authorization helpers
 */
public final class AuthorizationUtils {
    private AuthorizationUtils() {
    }
    
    public static String[] getBasicAuthParts(String data) {
        String authDecoded = null;
        try {
            authDecoded = new String(Base64Utility.decode(data));
        } catch (Exception ex) {
            throw new WebApplicationException(401);
        }
        String authInfo[] = authDecoded.split(":");
        if (authInfo.length == 2) {
            return authInfo;
        }
        throw new WebApplicationException(401);
    }
    
    public static String[] getAuthorizationParts(MessageContext mc) {
        return getAuthorizationParts(mc, Collections.singleton("Basic"));
    }
    
    public static String[] getAuthorizationParts(MessageContext mc,
                                                 Set<String> challenges) {
        List<String> headers = mc.getHttpHeaders().getRequestHeader("Authorization");
        if (headers.size() == 1) {
            String[] parts = headers.get(0).split(" ");
            if (parts.length == 2) {
                return parts;       
            }
        }
        throwAuthorizationFailure(challenges);
        return null;
    }
    
    public static void throwAuthorizationFailure(Set<String> challenges) {
        throwAuthorizationFailure(challenges, null);
    }
    
    public static void throwAuthorizationFailure(Set<String> challenges, String realm) {
        ResponseBuilder rb = Response.status(401);
        
        StringBuilder sb = new StringBuilder();
        for (String challenge : challenges) {
            if ("*".equals(challenge)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(challenge);
        }
        if (sb.length() > 0) {
            if (realm != null) {
                sb.append(" realm=\"" + realm + "\"");
            }
            rb.header(HttpHeaders.WWW_AUTHENTICATE, sb.toString());
        }
        Response r = rb.build();
        throw new WebApplicationException(r);
    }

}
