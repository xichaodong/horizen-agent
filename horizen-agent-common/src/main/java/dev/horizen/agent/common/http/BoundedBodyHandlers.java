package dev.horizen.agent.common.http;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 传输过程中终止过大的 HTTP 响应体，同时保留 HttpClient 截止时间与取消语义。
 */
public final class BoundedBodyHandlers {
    /**
     * 工具类私有构造器，避免创建没有独立运行状态的实例。
     */
    private BoundedBodyHandlers() {
    }

    /**
     * 计算或取得本方法声明的结果，供当前BoundedBodyHandlers处理步骤使用。
     *
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次操作返回的正文处理器结果。
     * @throws IllegalArgumentException 当前输入或运行状态不满足本方法的处理条件时抛出。
     */
    public static HttpResponse.BodyHandler<byte[]> bytes(long maximumBytes) {
        if (maximumBytes < 0 || maximumBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Invalid HTTP body limit");
        }
        return response -> new LimitedSubscriber(maximumBytes);
    }

    /**
     * 计算或取得本方法声明的结果，供当前BoundedBodyHandlers处理步骤使用。
     *
     * @param maximumBytes 最大的字节数，用于容量或传输限制。
     * @return 本次操作返回的正文处理器结果。
     */
    public static HttpResponse.BodyHandler<String> utf8(long maximumBytes) {
        return response ->
                HttpResponse.BodySubscribers.mapping(
                        bytes(maximumBytes).apply(response),
                        value -> new String(value, StandardCharsets.UTF_8));
    }

    /**
     * 有界正文Handlers内部的受限订阅者，封装该步骤需要的状态或输入输出。
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        /**
         * 被包装的原始实现，由本组件补充隔离、观测或恢复行为。
         */
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();

        /**
         * 最大的字节数，用于容量或传输限制。
         */
        private final long maximumBytes;

        /**
         * 当前响应或事件输入流的订阅，取消时终止后续数据接收。
         */
        private Flow.Subscription subscription;

        /**
         * 当前响应已经接收的字节数，用于检查读取容量。
         */
        private long received;

        /**
         * done的状态标记，用于选择当前组件的处理路径。
         */
        private boolean done;

        /**
         * 读取正文。
         *
         * @return 承接本次处理结果与失败的异步对象，实际执行由订阅或完成流程推进。
         */
        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        /**
         * 响应订阅。
         *
         * @param subscription 当前受限订阅者持有的订阅对象，供相应处理步骤使用。
         */
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        /**
         * 响应下一个。
         *
         * @param buffers buffers的有序集合，保留当前组件处理或协议输出所需的顺序。
         */
        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > maximumBytes) {
                    subscription.cancel();
                    onError(new IOException("HTTP body exceeds " + maximumBytes + " byte limit"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }

        /**
         * 响应错误。
         *
         * @param error 本次失败的异常，用于分类、传播或诊断。
         */
        @Override
        public void onError(Throwable error) {
            if (done) return;
            done = true;
            delegate.onError(error);
        }

        /**
         * 响应Complete。
         */
        @Override
        public void onComplete() {
            if (done) return;
            done = true;
            delegate.onComplete();
        }
    }
}
