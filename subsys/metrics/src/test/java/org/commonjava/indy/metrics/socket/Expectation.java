/**
 * Copyright (C) 2011-2017 Red Hat, Inc. (https://github.com/Commonjava/indy)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.metrics.socket;

import org.commonjava.indy.metrics.zabbix.sender.SenderResult;

/**
 * Created by xiabai on 5/9/17.
 */
public class Expectation
{
    private final String method;

    private final byte[] requestBody;

    private final SenderResult senderResult;

    public Expectation( String method, byte[] requestBody, SenderResult senderResult )
    {
        this.method = method;
        this.requestBody = requestBody;
        this.senderResult = senderResult;
    }

    public String getMethod()
    {
        return method;
    }

    public byte[] getRequestBody()
    {
        return requestBody;
    }

    public SenderResult getSenderResult()
    {
        return senderResult;
    }
}