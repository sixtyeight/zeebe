/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.client.impl.event;

import java.util.List;

import io.zeebe.client.event.RaftEvent;
import io.zeebe.transport.SocketAddress;

public class RaftEventImpl implements RaftEvent
{

    protected List<SocketAddress> members;

    @Override
    public List<SocketAddress> getMembers()
    {
        return members;
    }

    public void setMembers(final List<SocketAddress> members)
    {
        this.members = members;
    }

    @Override
    public String toString()
    {
        return "RaftEventImpl{" +
            "members=" + members +
            '}';
    }
}