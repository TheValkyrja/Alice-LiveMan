/*
 * <Alice LiveMan>
 * Copyright (C) <2018>  <NekoSunflower>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package site.alice.liveman.mediaproxy.proxytask;

import lombok.extern.slf4j.Slf4j;
import site.alice.liveman.mediaproxy.MediaProxyManager;

import javax.websocket.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class TwitcastingMediaProxyTask extends MediaProxyTask {

    private static final int                         MAX_RETRY_COUNT   = 20;
    private volatile     long                        LAST_RECV_TIME    = System.currentTimeMillis();
    private              AtomicInteger               retryCount        = new AtomicInteger(0);
    private transient    Session                     session           = null;
    private transient    BlockingQueue<byte[]>       bufferCache       = new ArrayBlockingQueue<>(20);
    private transient    List<BlockingQueue<byte[]>> bufferedQueueList = new CopyOnWriteArrayList<>();
    private transient    byte[]                      m4sHeader;

    public byte[] getM4sHeader() {
        return m4sHeader;
    }

    public TwitcastingMediaProxyTask(String videoId, URI sourceUrl) {
        super(videoId, sourceUrl);
    }

    @Override
    protected void runTask() {

        File m4sPath = new File(MediaProxyManager.getTempPath() + "/mp4/" + getVideoId() + "/");
        m4sPath.mkdirs();
        File mp4File = new File(m4sPath + "/index.mp4");
        try (FileOutputStream fos = new FileOutputStream(mp4File, true)) {
            session = connectToTwitcasting(fos);
            while (!getTerminated() && retryCount.get() < MAX_RETRY_COUNT) {
                long dt = System.currentTimeMillis() - LAST_RECV_TIME;
                if (dt > 1000) {
                    log.info(getVideoId() + "没有找到可以下载的片段，重试(" + retryCount.incrementAndGet() + "/" + MAX_RETRY_COUNT + ")次");
                } else {
                    m4sPath.setLastModified(System.currentTimeMillis());
                    retryCount.set(0);
                }
                Thread.sleep(1000);
            }
        } catch (Throwable e) {
            log.error(getVideoId() + "代理任务出错", e);
        }
    }

    private Session connectToTwitcasting(FileOutputStream fos) {
        WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
        ClientEndpointConfig clientEndpointConfig = ClientEndpointConfig.Builder.create().configurator(new ClientEndpointConfig.Configurator() {
            @Override
            public void beforeRequest(Map<String, List<String>> headers) {
                headers.put("Origin", Arrays.asList("https://twitcasting.tv"));
            }
        }).build();
        try {
            return webSocketContainer.connectToServer(new Endpoint() {
                @Override
                public void onOpen(Session session, EndpointConfig config) {
                    session.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
                    session.addMessageHandler(new MessageHandler.Whole<byte[]>() {
                        @Override
                        public void onMessage(byte[] message) {
                            if (getTerminated()) {
                                return;
                            }
                            if (m4sHeader == null) {
                                m4sHeader = message;
                            } else {
                                while (!bufferCache.offer(message)) {
                                    bufferCache.poll();
                                }
                                for (BlockingQueue<byte[]> bufferedQueue : bufferedQueueList) {
                                    while (!bufferedQueue.offer(message)) {
                                        bufferedQueue.poll();
                                    }
                                }
                            }
                            try {
                                fos.write(message);
                            } catch (IOException e) {
                                log.error(getVideoId() + "直播流写入失败", e);
                            }
                            LAST_RECV_TIME = System.currentTimeMillis();
                        }
                    });
                }

                @Override
                public void onClose(Session _session, CloseReason closeReason) {
                    session = null;
                    while (session == null && !getTerminated() && retryCount.get() < MAX_RETRY_COUNT) {
                        log.warn(getVideoId() + "WebSocket连接已断开[" + closeReason + "]，重试(" + retryCount.incrementAndGet() + "/" + MAX_RETRY_COUNT + ")次");
                        session = connectToTwitcasting(fos);
                    }
                }
            }, clientEndpointConfig, getSourceUrl());
        } catch (Throwable e) {
            log.error(getVideoId() + "WebSocket连接失败", e);
        }
        return null;
    }

    public void addBufferedQueue(BlockingQueue<byte[]> bufferedQueue) {
        bufferedQueue.addAll(bufferCache);
        bufferedQueueList.add(bufferedQueue);
    }

    public void removeBufferedQueue(BlockingQueue<byte[]> bufferedQueue) {
        bufferedQueueList.remove(bufferedQueue);
    }

    @Override
    protected void terminateTask() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException ignored) {

            }
        }
    }
}
