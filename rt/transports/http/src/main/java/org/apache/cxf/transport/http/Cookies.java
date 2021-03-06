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
package org.apache.cxf.transport.http;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;

public class Cookies {
    /**
     * Variables for holding session state if sessions are supposed to be maintained
     */
    private final Map<String, Cookie> sessionCookies = new ConcurrentHashMap<String, Cookie>(4, 0.75f, 4);
    private boolean maintainSession;
    
    public Map<String, Cookie> getSessionCookies() {
        return sessionCookies;
    }
    
    public void readFromConnection(HttpURLConnection connection) {
        if (maintainSession) {
            for (Map.Entry<String, List<String>> h : connection.getHeaderFields().entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(h.getKey())) {
                    handleSetCookie(h.getValue());
                }
            }
        }
    }
    
    public void writeToMessageHeaders(Message message) {
        //Do we need to maintain a session?
        maintainSession = MessageUtils.getContextualBoolean(message, Message.MAINTAIN_SESSION, false);
        
        //If we have any cookies and we are maintaining sessions, then use them        
        if (maintainSession && sessionCookies.size() > 0) {
            new Headers(message).writeSessionCookies(sessionCookies);
        }
    }

    /**
     * Given a list of current cookies and a new Set-Cookie: request, construct
     * a new set of current cookies and return it.
     * @param current Set of previously set cookies
     * @param header Text of a Set-Cookie: header
     * @return New set of cookies
     */
    private void handleSetCookie(List<String> headers) {
        if (headers == null || headers.size() == 0) {
            return;
        }
    
        for (String header : headers) {
            String[] cookies = StringUtils.split(header, ",");
            for (String cookie : cookies) {
                String[] parts = StringUtils.split(cookie, ";");
    
                String[] kv = StringUtils.split(parts[0], "=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String name = kv[0].trim();
                String value = kv[1].trim();
                Cookie newCookie = new Cookie(name, value);
    
                for (int i = 1; i < parts.length; i++) {
                    kv = StringUtils.split(parts[i], "=", 2);
                    name = kv[0].trim();
                    value = (kv.length > 1) ? kv[1].trim() : null;
                    if (name.equalsIgnoreCase(Cookie.DISCARD_ATTRIBUTE)) {
                        newCookie.setMaxAge(0);
                    } else if (name.equalsIgnoreCase(Cookie.MAX_AGE_ATTRIBUTE) && value != null) {
                        try {
                            newCookie.setMaxAge(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            // do nothing here
                        }
                    } else if (name.equalsIgnoreCase(Cookie.PATH_ATTRIBUTE) && value != null) {
                        newCookie.setPath(value);
                    }
                }
                if (newCookie.getMaxAge() != 0) {
                    sessionCookies.put(newCookie.getName(), newCookie);                    
                } else {
                    sessionCookies.remove(newCookie.getName());
                }
            }
        }
    }
}
