/*
 * Copyright 2021 The Analytic Zoo Authors
 *
 * Licensed under the Apache License,  Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,  software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,  either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.zoo.grpc;


import io.grpc.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import java.io.IOException;
import java.util.UUID;
import java.util.function.Function;

/**
 * All Analytics Zoo gRPC clients are based on ZooGrpcClient
 * To implement specific gRPC client, overwrite parseConfig() and loadServices() method
 */
public class ZooGrpcClient extends AbstractZooGrpc{
    protected static final Logger logger = LogManager.getLogger(ZooGrpcClient.class.getName());
    protected String target;
    protected final String clientUUID;
    protected ManagedChannel channel;
    public ZooGrpcClient(String[] args) {
        clientUUID = UUID.randomUUID().toString();
        this.args = args;
    }
    protected void parseConfig() throws IOException {}

    public void loadServices() {}

    public ManagedChannel getChannel() {
        return channel;
    }
    public void build() throws IOException {
        parseConfig();

        channel = ManagedChannelBuilder.forTarget(target)
                // Channels are secure by default (via SSL/TLS).
                .usePlaintext()
                .build();
        loadServices();
    }
    public <I, O> O call(Function<I, O> f, I msg) {
        O r = null;
        try {
            r = f.apply(msg);
        } catch (Exception e) {
            logger.warn("failed");
        } finally {
            return r;
        }

    }
}
