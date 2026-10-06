package dev.horizen.agent.web.bootstrap.model;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SDK 解析和共享连接，取消仅影响单个流式请求。
 */
public final class CancellableModelHttpTransport extends OkHttpTransport implements AutoCloseable {
    /**
     * streams的去重集合，供成员查找或范围检查使用。
     */
    private final Set<Dispatcher> streams = ConcurrentHashMap.newKeySet();

    /**
     * 组件是否已关闭，用于避免重复释放或继续接收新工作。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建Cancellable模型HTTP传输，初始化该组件所需的状态、配置或依赖。
     *
     * @param client 当前适配器使用的远端客户端，供实际网络或服务请求使用。
     * @param config 当前组件的配置与策略参数。
     */
    public CancellableModelHttpTransport(OkHttpClient client, HttpTransportConfig config) {
        super(client, config);
    }

    /**
     * 产生执行流并返回Cancellable模型HTTP传输。
     *
     * @param request 当前操作的请求参数。
     * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
     * @throws IOException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    @Override
    public Flux<String> stream(HttpRequest request) {
        return Flux.defer(
                () -> {
                    if (closed.get())
                        return Flux.error(new IllegalStateException("Model transport closed"));
                    // 各调度器分别跟踪调用，但共享托管执行器和连接池。
                    var dispatcher = new Dispatcher(getClient().dispatcher().executorService());
                    streams.add(dispatcher);
                    if (closed.get()) {
                        streams.remove(dispatcher);
                        return Flux.error(new IllegalStateException("Model transport closed"));
                    }
                    var cancelled = new AtomicBoolean();
                    var client =
                            getClient()
                                    .newBuilder()
                                    .dispatcher(dispatcher)
                                    .addInterceptor(
                                            chain -> {
                                                if (closed.get() || cancelled.get())
                                                    throw new IOException(
                                                            "Model request cancelled");
                                                return chain.proceed(chain.request());
                                            })
                                    .build();
                    return new OkHttpTransport(client, getConfig())
                            .stream(request)
                            // 在 SDK 释放阻塞读取器前，先取消 Socket 连接。
                            .doOnCancel(
                                    () -> {
                                        cancelled.set(true);
                                        dispatcher.cancelAll();
                                    })
                            .doFinally(signal -> streams.remove(dispatcher));
                });
    }

    /**
     * 结束当前对象的使用，执行该实现持有资源或执行句柄的清理。
     * 并发状态更新包含比较交换操作。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        streams.forEach(Dispatcher::cancelAll);
        getClient().dispatcher().cancelAll();
        super.close();
    }
}
