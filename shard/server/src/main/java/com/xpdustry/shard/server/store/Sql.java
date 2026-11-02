package com.xpdustry.shard.server.store;

import java.sql.*;

/** Small JDBC helpers. */
final class Sql{
    /** Binds parameters in order; null binds SQL NULL. */
    static void bind(PreparedStatement statement, Object... parameters) throws SQLException{
        for(int i = 0; i < parameters.length; i++){
            Object value = parameters[i];
            switch(value){
                case null -> statement.setNull(i + 1, Types.NULL);
                case String text -> statement.setString(i + 1, text);
                case Integer number -> statement.setInt(i + 1, number);
                case Long number -> statement.setLong(i + 1, number);
                case Boolean flag -> statement.setInt(i + 1, flag ? 1 : 0);
                default -> throw new IllegalArgumentException("cannot bind " + value.getClass().getName());
            }
        }
    }

    /** A nullable integer column. */
    static Long nullableLong(ResultSet rows, int column) throws SQLException{
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private Sql(){}
}
