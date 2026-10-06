package dev.horizen.agent.storage.jdbc.mapper;

import dev.horizen.agent.storage.jdbc.model.SessionHistoryRow;

import java.util.List;
import java.util.Map;

/**
 * 会话历史检索的 MyBatis 映射端口，按归属与查询游标读取记录。
 */
public interface SessionHistoryMapper {
    /**
     * 按映射语句的筛选与分页条件读取对应存储记录记录。
     *
     * @param parameters 调用参数集合，由相应工具或协议转换器解释。
     * @return 本次处理得到的结果集合。
     */
    List<String> visibleCurrent(Map<String, Object> parameters);

    /**
     * 按映射语句的筛选与分页条件读取对应存储记录记录。
     *
     * @param parameters 调用参数集合，由相应工具或协议转换器解释。
     * @return 本次处理得到的结果集合。
     */
    List<String> targetTitles(Map<String, Object> parameters);

    /**
     * 按映射语句的筛选与分页条件读取对应存储记录记录。
     *
     * @param parameters 调用参数集合，由相应工具或协议转换器解释。
     * @return 本次操作返回的长整型结果。
     */
    Long countHistory(Map<String, Object> parameters);

    /**
     * 按映射语句的筛选与分页条件读取对应存储记录记录。
     *
     * @param parameters 调用参数集合，由相应工具或协议转换器解释。
     * @return 本次处理得到的结果集合。
     */
    List<SessionHistoryRow> selectHistory(Map<String, Object> parameters);
}
