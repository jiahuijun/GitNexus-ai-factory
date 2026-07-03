package com.factory.ai.chat.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis TypeHandler：在 {@code List<ChatMessage>} 与 JSON 文本列之间双向转换。
 *
 * <p>用于 {@code chat_session.history} 列。使用 {@link TypeReference} 捕获泛型类型，
 * 确保 Jackson 反序列化时产出 {@code List<ChatMessage>}（record）而非
 * {@code List<LinkedHashMap>}（MyBatis-Plus 内置的 JacksonTypeHandler 无法处理泛型列表）。</p>
 *
 * <p>空值处理：DB 中 null/blank → 返回空 {@link ArrayList}（非 null），
 * 保证 {@code session.getHistory().add(...)} 不会 NPE。</p>
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
@MappedTypes(List.class)
public class ChatMessageListTypeHandler extends BaseTypeHandler<List<ChatMessage>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ChatMessage>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
            List<ChatMessage> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize ChatMessage list", e);
        }
    }

    @Override
    public List<ChatMessage> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<ChatMessage> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<ChatMessage> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<ChatMessage> parse(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return MAPPER.readValue(json, TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize ChatMessage list", e);
        }
    }
}
