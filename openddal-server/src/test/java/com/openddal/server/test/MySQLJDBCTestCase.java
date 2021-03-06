package com.openddal.server.test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Before;
import org.junit.Test;

import com.alibaba.druid.pool.DruidDataSource;
import com.openddal.util.JdbcUtils;


public class MySQLJDBCTestCase {
    
    private String MYSQL_DRIVER = "com.mysql.jdbc.Driver";
    private DruidDataSource datasource;
    
    @Before
    public void init() {
        datasource = new DruidDataSource();
        datasource.setMaxActive(20);
        datasource.setValidationQuery("select 1");
        datasource.setDriverClassName(MYSQL_DRIVER);
        datasource.setUrl("jdbc:mysql://localhost:6100/?connectTimeout=1000&amp;rewriteBatchedStatements=true");
        datasource.setUsername("root");
        datasource.setPassword("root");
    }
    
    @Test
    public void testConnection() throws Exception {
        try {
            Connection connection = datasource.getConnection();
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @Test
    public void testQuery() throws Exception {
        Connection conn = null;
        Statement stat = null;
        ResultSet set = null;
        try {
            conn = datasource.getConnection();
            stat = conn.createStatement();
            conn.setReadOnly(true);
            set = stat.executeQuery("select connection_id()");
            while (set.next()) {
                System.out.println(set.getInt(1));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            JdbcUtils.closeSilently(set);
            JdbcUtils.closeSilently(stat);
            JdbcUtils.closeSilently(conn);
        }
    }

}
