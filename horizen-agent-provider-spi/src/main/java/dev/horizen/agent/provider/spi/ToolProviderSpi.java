package dev.horizen.agent.provider.spi;

/**
 * 可选的进程内实现接口；HTTP 适配器通过 JSON 暴露相同 DTO。
 */
public interface ToolProviderSpi {
    /**
     * 计算或取得本方法声明的结果，供当前ToolProviderSpi处理步骤使用。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的提供方目录响应结果。
     */
    ProviderCatalogResponse catalog(ProviderCatalogRequest request);

    /**
     * 调用工具提供方Spi。
     *
     * @param request 当前操作的请求参数。
     * @return 本次操作返回的提供方调用响应结果。
     */
    ProviderInvokeResponse invoke(ProviderInvokeRequest request);
}
