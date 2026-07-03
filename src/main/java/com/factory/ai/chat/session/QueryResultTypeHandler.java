package com.factory.ai.chat.session;

import com.factory.ai.gitnexus.dto.QueryResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler：在 {@link QueryResult} 与 JSON 文本列之间双向转换。
 *
 * <p>用于 {@code chat_session.query_result} 列，该列以 TEXT 存储 JSON 序列化后的
 * {@link QueryResult}（含 {@code symbols} 和 {@code processNames}）。</p>
 *
 * <p>使用静态 {@link ObjectMapper} 实例（MyBatis 反射构造 TypeHandler，
 * 无法通过 Spring 注入）。{@link QueryResult} 是 record，Jackson 2.17+ 原生支持。</p>
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
@MappedTypes(QueryResult.class)
public class QueryResultTypeHandler extends BaseTypeHandler<QueryResult> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
            QueryResult parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize QueryResult", e);
        }
    }

    @Override
    public QueryResult getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public QueryResult getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public QueryResult getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private QueryResult parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, QueryResult.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize QueryResult", e);
        }
    }
}
