/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.sync.data.websocket.client;

import org.apache.shenyu.common.dto.WebsocketData;
import org.apache.shenyu.common.enums.ConfigGroupEnum;
import org.apache.shenyu.common.enums.DataEventTypeEnum;
import org.apache.shenyu.common.timer.AbstractRoundTask;
import org.apache.shenyu.common.timer.Timer;
import org.apache.shenyu.common.timer.TimerTask;
import org.apache.shenyu.common.timer.WheelTimerFactory;
import org.apache.shenyu.common.utils.GsonUtils;
import org.apache.shenyu.plugin.sync.data.websocket.handler.WebsocketDataHandler;
import org.apache.shenyu.sync.data.api.AuthDataSubscriber;
import org.apache.shenyu.sync.data.api.MetaDataSubscriber;
import org.apache.shenyu.sync.data.api.PluginDataSubscriber;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The type shenyu websocket client.
 * org.java-websocket:Java-WebSocket:1.5.0
 * 提供基础的ws-client能力
 */
public final class ShenyuWebsocketClient extends WebSocketClient {

    /**
     * logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ShenyuWebsocketClient.class);

    private volatile boolean alreadySync = Boolean.FALSE;

    private final WebsocketDataHandler websocketDataHandler;

    private final Timer timer;

    // 保存引用方便取消
    private TimerTask timerTask;

    /**
     * Instantiates a new shenyu websocket client.
     *
     * @param serverUri            the server uri
     * @param pluginDataSubscriber the plugin data subscriber
     * @param metaDataSubscribers  the meta data subscribers
     * @param authDataSubscribers  the auth data subscribers
     */
    public ShenyuWebsocketClient(final URI serverUri, final PluginDataSubscriber pluginDataSubscriber,
                                 final List<MetaDataSubscriber> metaDataSubscribers,
                                 final List<AuthDataSubscriber> authDataSubscribers
    ) {
        super(serverUri);
        this.websocketDataHandler = new WebsocketDataHandler(pluginDataSubscriber, metaDataSubscribers, authDataSubscribers);
        this.timer = WheelTimerFactory.getSharedTimer();
        this.connection();
    }

    /**
     * Instantiates a new shenyu websocket client.
     *
     * @param serverUri            the server uri
     * @param headers              the headers
     * @param pluginDataSubscriber the plugin data subscriber
     * @param metaDataSubscribers  the meta data subscribers
     * @param authDataSubscribers  the auth data subscribers
     */
    public ShenyuWebsocketClient(final URI serverUri, final Map<String, String> headers,
                                 final PluginDataSubscriber pluginDataSubscriber,
                                 final List<MetaDataSubscriber> metaDataSubscribers,
                                 final List<AuthDataSubscriber> authDataSubscribers) {
        super(serverUri, headers);
        this.websocketDataHandler = new WebsocketDataHandler(pluginDataSubscriber, metaDataSubscribers, authDataSubscribers);
        this.timer = WheelTimerFactory.getSharedTimer();
        this.connection();
    }

    private void connection() {
        this.connectBlocking();
        // 建立连接之后不管成功还是失败，开启定时健康检查任务，10ms一次
        this.timer.add(timerTask = new AbstractRoundTask(null, TimeUnit.SECONDS.toMillis(10)) {
            @Override
            public void doRun(final String key, final TimerTask timerTask) {
                healthCheck();
            }
        });
    }

    @Override
    public boolean connectBlocking() {
        boolean success = false;
        try {
            success = super.connectBlocking();
        } catch (Exception exception) {
            LOG.error("websocket connection server[{}] is error.....[{}]", this.getURI().toString(), exception.getMessage());
        }
        if (success) {
            LOG.info("websocket connection server[{}] is successful.....", this.getURI().toString());
        } else {
            LOG.warn("websocket connection server[{}] is error.....", this.getURI().toString());
        }
        return success;
    }

    /**
     * ws连接打开的时候触发回调
     *
     * @param serverHandshake
     */
    @Override
    public void onOpen(final ServerHandshake serverHandshake) {
        if (!alreadySync) {
            // 发送同步指令
            send(DataEventTypeEnum.MYSELF.name());
            alreadySync = true;
        }
    }

    /**
     * admin回包处理
     *
     * @param result
     */
    @Override
    public void onMessage(final String result) {
        handleResult(result);
    }

    @Override
    public void onClose(final int i, final String s, final boolean b) {
        this.close();
    }

    @Override
    public void onError(final Exception e) {
        LOG.error("websocket server[{}] is error.....", getURI(), e);
    }

    @Override
    public void close() {
        alreadySync = false;
        if (this.isOpen()) {
            super.close();
        }
    }

    /**
     * Now close.
     * now close. will cancel the task execution.
     */
    public void nowClose() {
        this.close();
        timerTask.cancel();
    }

    /**
     * 检查ws连接是否正常，是则发送ping指令，否则重连
     */
    private void healthCheck() {
        try {
            if (!this.isOpen()) {
                this.reconnectBlocking();
            } else {
                this.sendPing();
                LOG.debug("websocket send to [{}] ping message successful", this.getURI());
            }
        } catch (Exception e) {
            LOG.error("websocket connect is error :{}", e.getMessage());
        }
    }

    /**
     * 处理admin返回的数据
     *
     * @param result
     */
    private void handleResult(final String result) {
        LOG.info("handleResult({})", result);
        WebsocketData<?> websocketData = GsonUtils.getInstance().fromJson(result, WebsocketData.class);
        ConfigGroupEnum groupEnum = ConfigGroupEnum.acquireByName(websocketData.getGroupType());
        String eventType = websocketData.getEventType();
        String json = GsonUtils.getInstance().toJson(websocketData.getData());
        websocketDataHandler.executor(groupEnum, json, eventType);
    }
}
